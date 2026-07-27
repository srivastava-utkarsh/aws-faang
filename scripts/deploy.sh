#!/bin/bash
# =============================================================================
# OrderFlow — Phase 1 full deploy script
# =============================================================================
# Does everything in one run:
#   1. Validates prerequisites (AWS CLI, Docker, Java, Maven)
#   2. Builds the Lambda authorizer JAR and uploads it to S3
#   3. Deploys all 9 CloudFormation infra stacks (VPC → IAM → RDS → ...)
#   4. Builds the order-service Docker image
#   5. Pushes the image to ECR
#   6. Deploys the ECS service stack (needs image in ECR first)
#   7. Deploys the API Gateway stack (needs ECS ALB DNS first)
#   8. Prints the final API URL and a sample curl command to test it
#
# PREREQUISITES (must exist before running this script):
#   - S3 bucket: orderflow-cfn-artifacts-<account-id>  (create once manually)
#   - AWS CLI profile "orderflow" configured for ap-south-1
#   - Docker daemon running
#   - Java 21 + Maven installed
#
# USAGE:
#   export DB_MASTER_PASSWORD="SomeStr0ngPass!"   # min 8 chars
#   export JWT_SECRET="a-secret-that-is-at-least-32-chars!!"  # min 32 chars
#   chmod +x scripts/deploy.sh
#   ./scripts/deploy.sh              # deploys to dev in ap-south-1
#   ./scripts/deploy.sh prod         # deploys to prod
#   ./scripts/deploy.sh dev eu-west-1  # different region
#
# Do NOT run this at the same time as teardown.sh in another terminal. Both
# scripts touch the same stack names — if teardown deletes a stack (e.g. the
# VPC) while this is mid-creation of a dependent stack (e.g. RDS), the
# dependent stack's resources vanish out from under it and creation fails.
# Let one script fully finish (or Ctrl-C it) before starting the other.
# =============================================================================

set -euo pipefail
# set -e  → exit immediately if any command fails
# set -u  → treat unset variables as errors (catches typos like $REGIOM)
# set -o pipefail → if a pipe fails mid-way, the whole pipe is treated as failed

# ── CONFIG ────────────────────────────────────────────────────────────────────
ENV=${1:-dev}
REGION=${2:-ap-south-1}
PROFILE=orderflow
IMAGE_TAG="latest"   # change to a git SHA for traceable prod deploys

# Script directory so paths work from any cwd
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ── COLOUR HELPERS ───────────────────────────────────────────────────────────
# Makes the log output much easier to scan in a terminal
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Colour — resets formatting

# Prints a clearly visible section header so you know which phase you're in
section() {
  echo ""
  echo -e "${BOLD}${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
  echo -e "${BOLD}${BLUE}  $1${NC}"
  echo -e "${BOLD}${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

# Green tick for success
ok()   { echo -e "  ${GREEN}✓${NC} $1"; }

# Yellow info line
info() { echo -e "  ${CYAN}→${NC} $1"; }

# Red error — called just before exit
fail() { echo -e "  ${RED}✗ ERROR: $1${NC}"; }

# Shows the user what the next thing happening will be
next_step() {
  echo ""
  echo -e "  ${YELLOW}⟹  NEXT: $1${NC}"
}

# ── STEP 0: VALIDATE SECRETS ─────────────────────────────────────────────────
# Do this first — no point building JARs and images only to fail at CFN deploy
# because a secret was missing.

section "STEP 0 — Validating secrets and config"

# ⚠️  HARDCODED FOR TESTING — NEVER COMMIT THESE TO A REAL REPO ⚠️
# For real deployments: remove these defaults and export them in your shell instead.
DB_MASTER_PASSWORD="${DB_MASTER_PASSWORD:-TestPassword123!}"
JWT_SECRET="${JWT_SECRET:-this-is-a-test-jwt-secret-key-at-least-32-characters-long}"

# Validate minimum lengths so we don't get cryptic AWS errors later
if [ ${#DB_MASTER_PASSWORD} -lt 8 ]; then
  fail "DB_MASTER_PASSWORD must be at least 8 characters"
  exit 1
fi
if [ ${#JWT_SECRET} -lt 32 ]; then
  fail "JWT_SECRET must be at least 32 characters (HS256 minimum key size)"
  exit 1
fi

ok "DB_MASTER_PASSWORD is set (${#DB_MASTER_PASSWORD} chars)"
ok "JWT_SECRET is set (${#JWT_SECRET} chars)"
info "Environment : $ENV"
info "Region      : $REGION"
info "AWS Profile : $PROFILE"
info "Image tag   : $IMAGE_TAG"

# ── STEP 1: VALIDATE PREREQUISITES ───────────────────────────────────────────

section "STEP 1 — Checking prerequisites"

# Check AWS CLI
if ! command -v aws &>/dev/null; then
  fail "AWS CLI not found. Install from https://docs.aws.amazon.com/cli/latest/userguide/install-cliv2.html"
  exit 1
fi
ok "AWS CLI found: $(aws --version 2>&1 | head -1)"

# Check the 'orderflow' AWS profile exists and credentials are valid
# sts get-caller-identity is the standard "am I authenticated?" check
info "Checking AWS credentials for profile '$PROFILE'..."
ACCOUNT=$(aws sts get-caller-identity --profile "$PROFILE" --query Account --output text 2>/dev/null) || {
  fail "Cannot authenticate with profile '$PROFILE'. Run: aws configure --profile orderflow"
  exit 1
}
ok "AWS authenticated — Account ID: $ACCOUNT"
ARTIFACTS_BUCKET="orderflow-cfn-artifacts-${ACCOUNT}"
info "Artifacts S3 bucket: $ARTIFACTS_BUCKET"

# Verify the S3 artifacts bucket actually exists (must be created manually before first run)
if ! aws s3 ls "s3://$ARTIFACTS_BUCKET" --profile "$PROFILE" --region "$REGION" &>/dev/null; then
  fail "S3 bucket $ARTIFACTS_BUCKET not found."
  echo "      Create it first with:"
  echo "      aws s3 mb s3://$ARTIFACTS_BUCKET --region $REGION --profile $PROFILE"
  exit 1
fi
ok "S3 artifacts bucket exists: $ARTIFACTS_BUCKET"

# Check Docker
if ! command -v docker &>/dev/null; then
  fail "Docker not found. Install Docker Desktop from https://docs.docker.com/desktop/mac/"
  exit 1
fi
if ! docker info &>/dev/null; then
  fail "Docker daemon is not running. Start Docker Desktop first."
  exit 1
fi
ok "Docker is running: $(docker --version)"

# Check Java for Lambda authorizer build only (order-service builds in Docker)
# The Lambda JAR must be built on the host since it's uploaded to S3 before
# CloudFormation runs. But it's a plain Java module with no Spring Boot,
# so Java 11+ is fine for compiling it.
if ! command -v java &>/dev/null; then
  fail "Java not found. Install any Java 11+ from https://adoptium.net/"
  exit 1
fi
JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
if [ "$JAVA_VER" -lt 11 ]; then
  fail "Java 11+ required for Lambda authorizer build, found version $JAVA_VER"
  exit 1
fi
ok "Java found: $(java -version 2>&1 | head -1) — sufficient for Lambda build"

# Check Maven for Lambda authorizer build (order-service uses Docker multi-stage)
if ! command -v mvn &>/dev/null; then
  fail "Maven not found. Install from https://maven.apache.org/install.html"
  exit 1
fi
ok "Maven found: $(mvn -version | head -1)"

next_step "Build the Lambda authorizer JAR"

# ── STEP 2: BUILD LAMBDA AUTHORIZER JAR ──────────────────────────────────────
# The Lambda authorizer is a plain Java module (no Spring Boot).
# maven-shade-plugin packages it into a single fat JAR with all dependencies
# bundled — Lambda needs everything on the classpath in one file.
# We upload the JAR to S3 so CloudFormation can reference it when creating
# the Lambda function resource in api-gateway.yaml.

section "STEP 2 — Building Lambda JARs"

# Builds one Lambda module and uploads its shaded JAR to the artifacts
# bucket. The S3 key must match the CodeS3Key default in that Lambda's
# CloudFormation template.
#
# Usage: build_and_upload_lambda <module-dir-name>
build_and_upload_lambda() {
  local MODULE=$1
  local MODULE_DIR="$PROJECT_ROOT/services/$MODULE"

  info "Building $MODULE ..."
  # -DskipTests: tests run separately (mvn test per module), not during deploy.
  # -B (batch mode): suppresses download progress bars that make logs unreadable.
  cd "$MODULE_DIR"
  mvn package -DskipTests -B -q

  # The shade plugin writes <finalName>.jar plus an "original-*.jar" — take
  # the shaded one.
  local JAR
  JAR=$(ls target/${MODULE}*.jar | grep -v 'original' | head -1)
  if [ ! -f "$JAR" ]; then
    fail "$MODULE JAR not found in $MODULE_DIR/target/ — did mvn package succeed?"
    exit 1
  fi

  local S3_KEY="${MODULE}/${MODULE}.jar"
  info "Uploading to s3://$ARTIFACTS_BUCKET/$S3_KEY ($(du -h "$JAR" | cut -f1))"
  aws s3 cp "$JAR" "s3://$ARTIFACTS_BUCKET/$S3_KEY" \
    --profile "$PROFILE" \
    --region "$REGION" \
    --only-show-errors
  ok "$MODULE uploaded"

  cd "$PROJECT_ROOT"
}

build_and_upload_lambda "lambda-authorizer"        # Phase 1 — API Gateway JWT authorizer
build_and_upload_lambda "order-processor-lambda"   # Phase 2 — SQS consumer
build_and_upload_lambda "payment-lambda"           # Phase 2 — EventBridge target

next_step "Deploy CloudFormation infra stacks (VPC, IAM, RDS, DynamoDB, SQS, SNS, EventBridge, ECR, ECS Cluster)"

# ── CFN HELPER FUNCTION ───────────────────────────────────────────────────────
# Wraps `aws cloudformation deploy` with consistent flags, logging, and
# error handling. All stacks use the same pattern so this stays DRY.
#
# Usage: deploy_stack <stack-name> <template-path> [extra param overrides...]
#
# The `shift 2` trick: $1 and $2 are consumed as stack name and template,
# then "$@" captures any remaining arguments as extra --parameter-overrides.

deploy_stack() {
  local STACK_NAME=$1
  local TEMPLATE=$2
  shift 2  # now "$@" = any extra parameter-override pairs

  info "Deploying stack: $STACK_NAME"
  info "  Template : $TEMPLATE"
  [ $# -gt 0 ] && info "  Extra params: $*"

  aws cloudformation deploy \
    --template-file "$PROJECT_ROOT/$TEMPLATE" \
    --stack-name "$STACK_NAME" \
    --parameter-overrides Environment="$ENV" "$@" \
    --capabilities CAPABILITY_NAMED_IAM \
    --s3-bucket "$ARTIFACTS_BUCKET" \
    --profile "$PROFILE" \
    --region "$REGION"

  # Confirm the stack is actually in a good state — deploy can exit 0
  # even with "No changes to deploy" which is fine, but we want to catch
  # cases where the stack is in ROLLBACK_COMPLETE from a previous failure.
  STACK_STATUS=$(aws cloudformation describe-stacks \
    --stack-name "$STACK_NAME" \
    --profile "$PROFILE" \
    --region "$REGION" \
    --query "Stacks[0].StackStatus" \
    --output text 2>/dev/null)

  case "$STACK_STATUS" in
    CREATE_COMPLETE|UPDATE_COMPLETE|UPDATE_COMPLETE_CLEANUP_IN_PROGRESS)
      ok "Stack $STACK_NAME → $STACK_STATUS"
      ;;
    ROLLBACK_COMPLETE)
      fail "Stack $STACK_NAME is in ROLLBACK_COMPLETE — the previous create attempt failed and rolled back."
      echo "      The stack must be deleted before it can be recreated. Run:"
      echo "      aws cloudformation delete-stack --stack-name $STACK_NAME --profile $PROFILE --region $REGION"
      echo "      aws cloudformation wait stack-delete-complete --stack-name $STACK_NAME --profile $PROFILE --region $REGION"
      echo "      Then re-run this deploy script."
      echo "      To see why it failed:"
      echo "      aws cloudformation describe-stack-events --stack-name $STACK_NAME --profile $PROFILE --region $REGION --query \"StackEvents[?ResourceStatus=='CREATE_FAILED']\" --output table"
      exit 1
      ;;
    *)
      fail "Stack $STACK_NAME ended in unexpected status: $STACK_STATUS"
      echo "      Check CloudFormation console for event details:"
      echo "      https://console.aws.amazon.com/cloudformation/home?region=$REGION"
      exit 1
      ;;
  esac
}

# ── STEP 3: VPC ───────────────────────────────────────────────────────────────
# Foundation for everything. Every other stack imports outputs from this one.
# Creates: VPC, IGW, 2 public subnets, 2 private subnets, NAT Gateway, route
# tables, 3 SGs. NAT lets ECS tasks in private subnets pull from ECR and call
# SQS/DynamoDB. Tear the stack down after testing to avoid the ~$32/month
# idle cost — swapping to VPC Endpoints instead of NAT is a Phase 2 exercise
# for when this needs to stay up longer, not needed for a short test pass.
# Typical duration: ~2 minutes (NAT Gateway is the slow part of this stack).

section "STEP 3 — VPC (foundation networking)"
deploy_stack "orderflow-vpc-$ENV" "infra/vpc/vpc.yaml"

next_step "IAM roles"

# ── STEP 4: IAM ───────────────────────────────────────────────────────────────
# Creates 4 roles — one per compute unit, each with only what it needs:
#   orderflow-ecs-execution-{env}  → ECS agent: ECR pull + CW logs
#   orderflow-ecs-task-{env}       → App code: SQS send + DynamoDB CRUD
#   orderflow-lambda-{env}         → Payment/notification Lambdas (Phase 2)
#   orderflow-authorizer-{env}     → JWT Lambda: CW logs only (nothing else)
# Typical duration: ~30 seconds.

section "STEP 4 — IAM roles"
deploy_stack "orderflow-iam-$ENV" "infra/iam/roles.yaml"

next_step "RDS Aurora PostgreSQL (this is the slow one — ~5-10 minutes)"

# ── STEP 5: RDS ───────────────────────────────────────────────────────────────
# Aurora PostgreSQL 15.4 Serverless v2 in private subnets.
# Scaling: 0.5–4 ACU (auto, billed per ACU-hour → near-zero when idle).
# DB name: orderflow  |  User: orderflow_admin
# Flyway inside order-service will run migrations (create tables) on first
# startup — RDS just needs to exist and be reachable.
# ⚠️  This is the SLOWEST step (~5-10 minutes for Aurora to provision).
#     Go make a coffee.

section "STEP 5 — RDS Aurora PostgreSQL Serverless v2"
info "This step takes 5-10 minutes while Aurora provisions. Please wait..."
deploy_stack "orderflow-rds-$ENV" "infra/data/rds.yaml" \
  "DBMasterPassword=$DB_MASTER_PASSWORD"

next_step "DynamoDB idempotency table"

# ── STEP 6: DYNAMODB ─────────────────────────────────────────────────────────
# Single table: orderflow-idempotency-{env}
# PK: idempotencyKey (String)  |  TTL: expiresAt (24h auto-expiry)
# PAY_PER_REQUEST billing — free when idle, scales automatically.
# Used by IdempotencyService to prevent duplicate order creation.
# Typical duration: ~20 seconds.

section "STEP 6 — DynamoDB idempotency table"
deploy_stack "orderflow-dynamodb-$ENV" "infra/data/dynamodb.yaml"

next_step "SQS queues"

# ── STEP 7: SQS ──────────────────────────────────────────────────────────────
# Creates 2 queues:
#   orderflow-order-queue-{env}  → main queue (VisibilityTimeout 30s, 24h retention)
#   orderflow-order-dlq-{env}    → dead-letter queue (14-day retention)
# After 3 failed processing attempts, SQS moves the message to the DLQ
# automatically — no code needed for this.
# Typical duration: ~20 seconds.

section "STEP 7 — SQS queues (order queue + DLQ)"
deploy_stack "orderflow-sqs-$ENV" "infra/messaging/sqs.yaml"

next_step "SNS notification topic + email/SMS queues"

# ── STEP 8: SNS ──────────────────────────────────────────────────────────────
# Creates: orderflow-notifications-{env} SNS topic
# Fan-out subscriptions (RawMessageDelivery=true):
#   → orderflow-email-queue-{env}  (+ email DLQ)
#   → orderflow-sms-queue-{env}    (+ SMS DLQ)
# EventBridge will publish OrderConfirmed events here (Rule 1 in eventbridge.yaml).
# The notification Lambda (Phase 2/3) will consume from these SQS queues.
# Typical duration: ~30 seconds.

section "STEP 8 — SNS topic + email/SMS SQS subscriptions"
deploy_stack "orderflow-sns-$ENV" "infra/messaging/sns.yaml"

next_step "EventBridge custom event bus + routing rules"

# ── STEP 9: EVENTBRIDGE ──────────────────────────────────────────────────────
# Creates: orderflow-order-bus-{env} custom event bus
# Rules defined (some disabled until their Lambda targets exist):
#   Rule 1 (ENABLED) : OrderConfirmed → SNS notification topic
#   Rule 2 (DISABLED): OrderConfirmed → orderflow-payment Lambda  (Phase 2)
#   Rule 3 (DISABLED): all order events → orderflow-archiver Lambda (Phase 3)
# Typical duration: ~30 seconds.

section "STEP 9 — EventBridge custom bus + routing rules"
deploy_stack "orderflow-eventbridge-$ENV" "infra/messaging/eventbridge.yaml"

next_step "ECR repositories (Docker image registries)"

# ── STEP 10: ECR ─────────────────────────────────────────────────────────────
# Creates 2 Docker image repositories:
#   orderflow/order-service-{env}     ← we push to this one below
#   orderflow/inventory-service-{env} ← Phase 2 (pre-provisioned now)
# ScanOnPush=true: ECR checks images for known CVEs on every docker push.
# Lifecycle policy: keeps last 10 images, deletes older ones automatically.
# Typical duration: ~20 seconds.

section "STEP 10 — ECR repositories"
deploy_stack "orderflow-ecr-$ENV" "infra/compute/ecr.yaml"

# Grab the ECR repo URIs from the stack outputs so we can use them for docker
# push. This avoids hardcoding the account ID and region in multiple places.
info "Fetching ECR repo URIs from stack outputs..."
ECR_REPO_URI=$(aws cloudformation describe-stacks \
  --stack-name "orderflow-ecr-$ENV" \
  --profile "$PROFILE" \
  --region "$REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='OrderServiceRepoUri'].OutputValue" \
  --output text)

INVENTORY_REPO_URI=$(aws cloudformation describe-stacks \
  --stack-name "orderflow-ecr-$ENV" \
  --profile "$PROFILE" \
  --region "$REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='InventoryServiceRepoUri'].OutputValue" \
  --output text)

if [ -z "$ECR_REPO_URI" ] || [ -z "$INVENTORY_REPO_URI" ]; then
  fail "Could not read repo URIs from orderflow-ecr-$ENV stack outputs"
  exit 1
fi
ok "order-service repo    : $ECR_REPO_URI"
ok "inventory-service repo: $INVENTORY_REPO_URI"

next_step "ECS cluster"

# ── STEP 11: ECS CLUSTER ─────────────────────────────────────────────────────
# Creates: orderflow-cluster-{env}
# Capacity providers: FARGATE + FARGATE_SPOT
# Dev default: FARGATE_SPOT (up to 70% cheaper, can be interrupted with 2-min notice)
# Container Insights: enabled — publishes per-task CPU/memory/network metrics to CW.
# Typical duration: ~30 seconds.

section "STEP 11 — ECS cluster"
deploy_stack "orderflow-ecs-cluster-$ENV" "infra/compute/ecs-cluster.yaml"

next_step "Build service Docker images and push to ECR"

# ── STEP 12: BUILD + PUSH SERVICE IMAGES ──────────────────────────────────────
# Images must be in ECR BEFORE the ECS service stacks deploy, because each
# task definition references an image URI. If the image isn't there, ECS
# fails to launch tasks and the stack rolls back.
#
# The Dockerfile is a multi-stage build:
#   Stage 1 (build): eclipse-temurin:21-jdk-alpine — compiles the JAR
#   Stage 2 (jlink): strips the JRE to only the modules the app uses
#   Stage 3 (runtime): alpine + custom JRE + the JAR, non-root user

section "STEP 12 — Build and push service Docker images"

# Authenticate Docker to ECR once for both pushes.
# ECR uses short-lived tokens (valid 12 hours). This fetches a fresh one.
info "Authenticating Docker with ECR..."
aws ecr get-login-password \
  --region "$REGION" \
  --profile "$PROFILE" \
  | docker login \
      --username AWS \
      --password-stdin \
      "$ACCOUNT.dkr.ecr.$REGION.amazonaws.com"
ok "Docker authenticated with ECR"

# Usage: build_and_push_image <service-dir-name> <ecr-repo-uri>
build_and_push_image() {
  local SERVICE=$1
  local REPO_URI=$2

  info "Building $SERVICE image (compiles the JAR inside the container — ~2-3 min first run)..."
  cd "$PROJECT_ROOT/services/$SERVICE"

  # Two tags: :latest for the task definition to reference, and a timestamp
  # tag so ECR keeps an identifiable history of what was deployed when.
  docker build \
    --tag "$REPO_URI:$IMAGE_TAG" \
    --tag "$REPO_URI:$(date +%Y%m%d%H%M%S)" \
    .
  ok "$SERVICE image built"

  info "Pushing $SERVICE to ECR..."
  docker push "$REPO_URI:$IMAGE_TAG"
  ok "$SERVICE pushed: $REPO_URI:$IMAGE_TAG"

  cd "$PROJECT_ROOT"
}

build_and_push_image "order-service"     "$ECR_REPO_URI"
build_and_push_image "inventory-service" "$INVENTORY_REPO_URI"

next_step "Deploy ECS service (ALB + task definition + service)"

# ── STEP 13: ECS SERVICE ─────────────────────────────────────────────────────
# This is the biggest stack — creates several resources at once:
#   - ALB (internet-facing, public subnets, HTTP :80)
#   - Target group (health check: GET /actuator/health → 200)
#   - Task definition (0.5 vCPU / 1 GB, ECR image, all env vars injected)
#   - ECS service (2 desired tasks, private subnets, Fargate)
#   - CloudWatch log group: /ecs/orderflow-order-service-{env}
#
# ⚠️  CloudFormation waits until BOTH tasks pass health checks before marking
#     CREATE_COMPLETE. Each task: pulls image (via NAT) → starts JVM → Spring
#     Boot init → Flyway migration runs (creates DB tables on first deploy)
#     → /actuator/health 200. Expect 3-5 minutes. If tasks get stuck in
#     PROVISIONING/STOPPED, check the ECS service's Events tab first —
#     CannotPullContainerError there usually means the VPC stack's NAT
#     Gateway didn't deploy, or the image was never pushed to ECR (step 12).

section "STEP 13 — ECS service (ALB + Fargate tasks)"
info "Waiting for 2 tasks to be healthy — expect 3-5 minutes..."
deploy_stack "orderflow-ecs-service-$ENV" "infra/compute/ecs-service.yaml" \
  "DBMasterPassword=$DB_MASTER_PASSWORD" \
  "ImageTag=$IMAGE_TAG"

next_step "Deploy API Gateway (REST API + Lambda authorizer)"

# ── STEP 14: API GATEWAY ─────────────────────────────────────────────────────
# The public entry point — the only thing clients ever call directly.
# Creates:
#   - Lambda function: orderflow-authorizer-{env} (Java 21, 512MB, from S3 JAR)
#   - REST API: orderflow-api-{env} with REGIONAL endpoint
#   - REQUEST authorizer (JWT) with 5-min result cache
#   - POST /orders  — proxied to ALB, injects X-User-Id header
#   - GET  /orders/{orderId} — proxied to ALB, injects X-User-Id header
#   - Deployment + stage "{env}"
#   - CW log group: /aws/lambda/orderflow-authorizer-{env} (14-day retention)
# Typical duration: ~1 minute.

section "STEP 14 — API Gateway + Lambda authorizer"
deploy_stack "orderflow-api-$ENV" "infra/api/api-gateway.yaml" \
  "JwtSecret=$JWT_SECRET"

next_step "Phase 2 — inventory-service behind an internal ALB"

# ══ PHASE 2 STACKS ═══════════════════════════════════════════════════════════
# Everything above delivers "order lands in SQS". The three stacks below are
# what finally consume that message and drive the order to PAID.

# ── STEP 15: INVENTORY SERVICE ───────────────────────────────────────────────
# ECS Fargate service behind an INTERNAL ALB (private IPs only — unreachable
# from the internet by routing, not just by security group).
# On first boot Flyway creates the `inventory` schema, its own
# flyway_schema_history, the stock/stock_reservations tables, and seeds a
# handful of products so the flow is testable immediately.
# Must deploy BEFORE the order-processor Lambda, which imports this stack's
# ALB DNS name as an environment variable.

section "STEP 15 — inventory-service (internal ALB + Fargate task)"
info "Waiting for the task to pass health checks — expect 3-5 minutes..."
deploy_stack "orderflow-inventory-service-$ENV" "infra/compute/inventory-service.yaml" \
  "DBMasterPassword=$DB_MASTER_PASSWORD" \
  "ImageTag=$IMAGE_TAG"

next_step "order-processor Lambda (the SQS consumer)"

# ── STEP 16: ORDER PROCESSOR LAMBDA ──────────────────────────────────────────
# The piece that was missing at the end of Phase 1: something that actually
# drains the order queue. VPC-attached so it can reach the internal ALB.
# Creates the function plus the SQS event source mapping (BatchSize 10,
# ReportBatchItemFailures so one poison message doesn't retry its 9 healthy
# neighbours).

section "STEP 16 — order-processor Lambda + SQS trigger"
deploy_stack "orderflow-order-processor-$ENV" "infra/compute/lambda-order-processor.yaml"

next_step "payment Lambda (EventBridge target)"

# ── STEP 17: PAYMENT LAMBDA ──────────────────────────────────────────────────
# Triggered by the OrderConfirmed rule on the custom bus. Connects to Aurora
# directly over JDBC and flips the order to PAID / PAYMENT_FAILED with a
# conditional UPDATE (".. AND status = 'PENDING'"), which is what makes a
# duplicate EventBridge delivery harmless.
# PaymentDeclineAbove makes the failure path reproducible: any order totalling
# more than this is declined on purpose.

section "STEP 17 — payment Lambda (EventBridge target)"
deploy_stack "orderflow-payment-$ENV" "infra/compute/lambda-payment.yaml" \
  "DBMasterPassword=$DB_MASTER_PASSWORD" \
  "PaymentDeclineAbove=10000"

next_step "Fetch API URL and print test commands"

# ── STEP 18: PRINT SUMMARY + TEST COMMANDS ───────────────────────────────────

section "STEP 18 — Deployment complete! Summary + test commands"

# Fetch the API invoke URL from the stack output
API_URL=$(aws cloudformation describe-stacks \
  --stack-name "orderflow-api-$ENV" \
  --profile "$PROFILE" \
  --region "$REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='ApiInvokeUrl'].OutputValue" \
  --output text)

# Print a summary of every stack and its current status
echo ""
echo -e "${BOLD}  CloudFormation stacks:${NC}"
for STACK in \
  "orderflow-vpc-$ENV" \
  "orderflow-iam-$ENV" \
  "orderflow-rds-$ENV" \
  "orderflow-dynamodb-$ENV" \
  "orderflow-sqs-$ENV" \
  "orderflow-sns-$ENV" \
  "orderflow-eventbridge-$ENV" \
  "orderflow-ecr-$ENV" \
  "orderflow-ecs-cluster-$ENV" \
  "orderflow-ecs-service-$ENV" \
  "orderflow-api-$ENV" \
  "orderflow-inventory-service-$ENV" \
  "orderflow-order-processor-$ENV" \
  "orderflow-payment-$ENV"
do
  STATUS=$(aws cloudformation describe-stacks \
    --stack-name "$STACK" \
    --profile "$PROFILE" \
    --region "$REGION" \
    --query "Stacks[0].StackStatus" \
    --output text 2>/dev/null || echo "NOT_FOUND")
  printf "    %-40s %s\n" "$STACK" "$STATUS"
done

echo ""
echo -e "${BOLD}  Key endpoints:${NC}"
echo "    API URL : $API_URL"
echo "    Region  : $REGION"
echo "    Account : $ACCOUNT"
echo ""
echo -e "${BOLD}  ─── HOW TO TEST ───────────────────────────────────────────${NC}"
echo ""
echo "  1. Generate a test JWT (uses openssl — no Cognito needed):"
echo ""
echo "     export JWT_SECRET=\"$JWT_SECRET\""
echo "     TOKEN=\$(./scripts/generate-test-jwt.sh user-123)"
echo "     echo \$TOKEN"
echo ""
echo "  2. Create an order (expect HTTP 202):"
echo ""
echo "     curl -s -X POST $API_URL/orders \\"
echo "       -H \"Authorization: Bearer \$TOKEN\" \\"
echo "       -H \"X-Idempotency-Key: \$(uuidgen)\" \\"
echo "       -H \"Content-Type: application/json\" \\"
echo "       -d '{\"userId\":\"user-123\",\"items\":[{\"productId\":\"prod-001\",\"quantity\":2,\"unitPrice\":49.99}]}' \\"
echo "       | jq ."
echo ""
echo "  3. Wait ~5 seconds, then read it back — status should now be PAID,"
echo "     NOT the PENDING it was created as. That flip is the whole of"
echo "     Phase 2 working: SQS -> order-processor -> inventory-service"
echo "     -> EventBridge -> payment Lambda -> RDS."
echo ""
echo "     curl -s \$API_URL/orders/ORDER_ID \\"
echo "       -H \"Authorization: Bearer \$TOKEN\" | jq ."
echo ""
echo "  4. Test auth rejection (expect 401):"
echo ""
echo "     curl -s -o /dev/null -w \"%{http_code}\" \$API_URL/orders/some-id"
echo ""
echo "  5. Exercise the PAYMENT_FAILED path — any order over 10000 total is"
echo "     declined on purpose (PaymentDeclineAbove), so status becomes"
echo "     PAYMENT_FAILED instead of PAID:"
echo ""
echo "       -d '{\"userId\":\"user-123\",\"items\":[{\"productId\":\"prod-001\",\"quantity\":1,\"unitPrice\":25000}]}'"
echo ""
echo "  6. Exercise the OUT-OF-STOCK path — 'prod-003' is seeded with only 25"
echo "     units, so ordering more than that leaves the order PENDING and"
echo "     publishes OrderFailed (no payment is ever attempted):"
echo ""
echo "       -d '{\"userId\":\"user-123\",\"items\":[{\"productId\":\"prod-003\",\"quantity\":999,\"unitPrice\":5}]}'"
echo ""
echo -e "${BOLD}  ─── WHAT TO CHECK IN AWS CONSOLE ──────────────────────────${NC}"
echo ""
echo "    CloudFormation → all 14 stacks = CREATE_COMPLETE"
echo "    ECS → orderflow-cluster-$ENV → 2 services, both Running"
echo "    RDS → cluster status = available"
echo "    SQS → order-queue depth returns to 0 (the consumer drains it now)"
echo "    SQS → order-dlq message count = 0 (no failures)"
echo "    Lambda → orderflow-order-processor-$ENV → invocations > 0"
echo "    Lambda → orderflow-payment-$ENV → invocations > 0"
echo ""
echo -e "${BOLD}  Useful logs:${NC}"
echo "    aws logs tail /aws/lambda/orderflow-order-processor-$ENV --since 10m --follow"
echo "    aws logs tail /aws/lambda/orderflow-payment-$ENV --since 10m --follow"
echo "    aws logs tail /ecs/orderflow-inventory-service-$ENV --since 10m --follow"
echo ""
echo -e "${GREEN}${BOLD}  ✅  OrderFlow Phase 1 + 2 deployed to $ENV ($REGION)${NC}"
echo ""

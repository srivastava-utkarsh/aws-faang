#!/bin/bash
# =============================================================================
# OrderFlow — Phase 1 teardown script
# =============================================================================
# Deletes every stack deploy.sh created, in the EXACT REVERSE of deploy order.
# This matters: CloudFormation refuses to delete a stack whose exported
# values are still imported by another live stack (e.g. you can't delete the
# VPC while the RDS stack still references its subnets). Reverse-of-deploy
# order is the one ordering guaranteed to never hit that error.
#
# Why tear down at all: NAT Gateway (~$0.045/hr) and the Aurora Serverless v2
# writer (billed per ACU-hour even near-idle) are the two things that
# actually cost money while sitting idle between test sessions.
#
# USAGE:
#   ./scripts/teardown.sh              # prompts for confirmation, dev/ap-south-1
#   ./scripts/teardown.sh dev ap-south-1 --yes   # skip confirmation (CI use)
#
# Do NOT run this at the same time as deploy.sh in another terminal. Both
# scripts touch the same stack names — if teardown deletes a stack (e.g. the
# VPC) while deploy is mid-creation of a dependent stack (e.g. RDS), the
# dependent stack's resources vanish out from under it and creation fails.
# Let one script fully finish (or Ctrl-C it) before starting the other.
# =============================================================================

set -euo pipefail

ENV=${1:-dev}
REGION=${2:-ap-south-1}
PROFILE=orderflow
SKIP_CONFIRM=false
for arg in "$@"; do
  [ "$arg" = "--yes" ] && SKIP_CONFIRM=true
done

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

section() {
  echo ""
  echo -e "${BOLD}${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
  echo -e "${BOLD}${BLUE}  $1${NC}"
  echo -e "${BOLD}${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}
ok()   { echo -e "  ${GREEN}✓${NC} $1"; }
info() { echo -e "  ${CYAN}→${NC} $1"; }
fail() { echo -e "  ${RED}✗ ERROR: $1${NC}"; }

section "OrderFlow teardown — ENV=$ENV REGION=$REGION"

echo ""
echo -e "  ${YELLOW}This permanently deletes every orderflow-*-$ENV stack:${NC}"
echo "    - SQS queues: any messages sitting in them are deleted with the queue"
echo "    - DynamoDB idempotency table: deleted (disposable, TTL'd anyway)"
echo "    - RDS Aurora: DeletionPolicy=Delete — no final snapshot is taken,"
echo "      the cluster and all its data are gone immediately and permanently"
echo "    - Next deploy.sh run creates everything fresh and empty — there is"
echo "      no continuity of orders/messages across a teardown + redeploy"
echo ""

if [ "$SKIP_CONFIRM" != true ]; then
  read -r -p "  Type 'yes' to continue: " CONFIRM
  if [ "$CONFIRM" != "yes" ]; then
    info "Aborted — nothing deleted."
    exit 0
  fi
fi

# Exact reverse of deploy.sh's stack order.
STACKS=(
  # Phase 2 first — these are the newest and depend on everything below.
  # The two Lambdas must go before the VPC (they hold ENIs in its subnets)
  # and before inventory-service (whose ALB DNS the processor imports).
  "orderflow-payment-$ENV"
  "orderflow-order-processor-$ENV"
  "orderflow-inventory-service-$ENV"
  # Phase 1
  "orderflow-api-$ENV"
  "orderflow-ecs-service-$ENV"
  "orderflow-ecs-cluster-$ENV"
  "orderflow-ecr-$ENV"
  "orderflow-eventbridge-$ENV"
  "orderflow-sns-$ENV"
  "orderflow-sqs-$ENV"
  "orderflow-dynamodb-$ENV"
  "orderflow-rds-$ENV"
  "orderflow-iam-$ENV"
  "orderflow-vpc-$ENV"
)

for STACK in "${STACKS[@]}"; do
  section "Deleting $STACK"

  EXISTS=$(aws cloudformation describe-stacks --stack-name "$STACK" \
    --profile "$PROFILE" --region "$REGION" \
    --query "Stacks[0].StackName" --output text 2>/dev/null || echo "NONE")

  if [ "$EXISTS" = "NONE" ]; then
    info "$STACK doesn't exist — skipping"
    continue
  fi

  info "Requesting deletion..."
  aws cloudformation delete-stack --stack-name "$STACK" --profile "$PROFILE" --region "$REGION"

  info "Waiting for deletion to complete (RDS is still the slowest — a few minutes to tear down the cluster, but no final snapshot step anymore)..."
  if aws cloudformation wait stack-delete-complete --stack-name "$STACK" --profile "$PROFILE" --region "$REGION"; then
    ok "$STACK deleted"
  else
    fail "$STACK failed to delete — check the CloudFormation console for the reason before continuing"
    exit 1
  fi
done

section "✅ Teardown complete"
info "Every orderflow-*-$ENV stack is gone. Run ./scripts/deploy.sh to start fresh."

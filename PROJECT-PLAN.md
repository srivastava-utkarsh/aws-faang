# OrderFlow — Master Plan

Learning AWS at staff-engineer depth by building a FAANG-style event-driven
order management system, using only: EC2, Lambda, ECS, EKS, S3, RDS, DynamoDB,
VPC, IAM, CloudWatch, API Gateway, SQS, SNS, EventBridge, CloudFormation.
Stack: Java 21 + Spring Boot 4. Target markets: India, UK, Germany.

## Architecture (3 flows)

1. **Request lifecycle** — Client → API Gateway → Lambda authorizer (JWT) →
   Order Service (ECS Fargate) → DynamoDB idempotency check → RDS Aurora
   (INSERT, status=PENDING) → SQS order queue → `202 Accepted`.
2. **Event-driven fanout** — SQS → Lambda order processor → Inventory Service
   (ECS) reserves stock → EventBridge `OrderConfirmed` → fan-out to
   Payment Lambda (→ RDS status=PAID), SNS → notification Lambda (email/SMS),
   S3 archiver (audit trail) → CloudWatch metrics.
3. **Failure & recovery** — SQS visibility timeout retries (maxReceiveCount=3)
   → DLQ → CloudWatch alarm → SNS alert → on-call → manual inspect/redrive
   Lambda → back to main queue.

## Current repo state

```
aws-orderflow/
├── infra/
│   ├── vpc/vpc.yaml                 ✅ done
│   ├── iam/roles.yaml               ✅ done (+ AuthorizerLambdaRole)
│   ├── data/rds.yaml                ✅ done
│   ├── data/dynamodb.yaml           ✅ done
│   ├── messaging/sqs.yaml           ✅ done
│   ├── messaging/sns.yaml           ✅ done
│   ├── messaging/eventbridge.yaml   ✅ done
│   ├── compute/ecr.yaml             ✅ done
│   ├── compute/ecs-cluster.yaml     ✅ done
│   ├── compute/ecs-service.yaml     ✅ done (ALB + task def + service)
│   ├── api/api-gateway.yaml         ✅ done (REST API + JWT authorizer wiring)
│   ├── observability/               🔲 empty (cloudwatch.yaml — alarms + dashboard)
│   └── master.yaml                  🔲 missing (phase 4 root stack)
├── scripts/
│   ├── deploy.sh                    ✅ fixed (was pointing at infrastructure/, repo uses infra/)
│   └── generate-test-jwt.sh         ✅ done (mints HS256 test JWTs, no Cognito needed)
├── docs/                            🔲 empty
└── services/
    ├── order-service/               ✅ done (Phase 1)
    ├── lambda-authorizer/           ✅ done (Phase 1 — see below)
    ├── inventory-service/           🔲 phase 2
    ├── payment (Lambda)/            🔲 phase 2
    ├── notification (Lambda)/       🔲 phase 2
    └── archiver (Lambda)/           🔲 phase 3
```

**Phase 1 is deployed and verified in AWS** — an order created via curl/Postman
is confirmed landing in RDS + DynamoDB + SQS. Phase 2 is planned (see the
section near the end of this file) and not yet built.

## Build phases

| Phase | Scope |
|---|---|
| 1 | Foundation infra + order-service + Lambda JWT authorizer + API Gateway — **deployed & verified** |
| 2 | inventory-service + order-processor Lambda (SQS consumer) + payment Lambda — **built, ready to deploy** |
| 3 | SNS notification Lambda + S3 archiver Lambda + DLQ redrive Lambda + `cloudwatch.yaml` |
| 4 | EKS migration option + `master.yaml` root stack + load test + **architecture dashboard** (infographic view of which services connect to what, where data is stored, how it's read — requested, deferred to here) |

## Immediate step — done

`services/order-service` is complete end-to-end: Flyway migration
(`orders`/`order_items`, `NUMERIC`, `TIMESTAMPTZ`, generated `line_total`),
JPA entities, repository, DTOs, `IdempotencyService` (DynamoDB conditional
write + TTL), `OrderQueuePublisher` (SQS), `OrderController`
(`POST /orders`, `GET /orders/{id}`), `AwsConfig`, `application.yaml`,
`Dockerfile`. Compiles clean.

`infra/compute/ecs-service.yaml` and `infra/api/api-gateway.yaml` are now
written too, plus `services/lambda-authorizer` (see below) — Phase 1 is
code-complete. Nothing is deployed to AWS yet; that's the next section.

---

# Lambda Authorizer (JWT) — what was built and why

`services/lambda-authorizer` is a **separate, plain Java Lambda module —
no Spring Boot.** This function runs on (almost) every single `/orders`
call, on the hot path, before the Order Service ever sees the request. A
Spring Boot cold start (2–5s) here would tax every request until the
container's warm; Order Service can afford Spring Boot precisely because
it's a long-lived, always-warm ECS task, not a per-invocation function.
Different jobs, different runtime shape.

**Files:**
- `pom.xml` — `aws-lambda-java-core` + `aws-lambda-java-events` (event
  types), `jjwt-api`/`jjwt-impl`/`jjwt-jackson` (JWT verification), built
  into one shaded jar via `maven-shade-plugin` (Lambda needs every
  dependency on the classpath in a single artifact).
- `JwtAuthorizerHandler.java` — a `RequestHandler<APIGatewayCustomAuthorizerEvent, Map<String,Object>>`.
  Pulls the `Authorization: Bearer <token>` header, verifies the HS256
  signature and expiry with `jjwt`, and either returns an Allow policy
  (with the verified `userId` in `context`) or throws
  `new RuntimeException("Unauthorized")`.
- `PolicyBuilder.java` — builds the exact JSON shape API Gateway expects
  back (`principalId` + `policyDocument` + `context`).

**Why HS256 + a Lambda environment variable, not Cognito/RS256+JWKS:**
this project's approved 15-service list has no Cognito and no Secrets
Manager. A shared HS256 secret passed as a Lambda env var (encrypted at
rest by Lambda's own default AWS-owned KMS key — a platform feature, not
an extra service to provision) is the simplest option that stays inside
the constraint. In a real system with Cognito available, you'd use
RS256 + a JWKS endpoint instead and never share a secret at all.

**The exact 401 mechanic:** for REST API custom authorizers, if the
Lambda throws an exception whose message is *precisely* the string
`"Unauthorized"`, API Gateway maps that to an HTTP 401 response. Any other
uncaught exception becomes a 500. This is why every rejection path in the
handler throws that specific string rather than a descriptive message.

**Why `context.authorizer.userId` needs an explicit mapping:** the ALB
integration in `api-gateway.yaml` uses `HTTP_PROXY`, which passes the
request through as-is — it does **not** automatically forward the
authorizer's `context` map as a header the way some managed integrations
do. `api-gateway.yaml`'s `OrdersPostMethod`/`OrderGetMethod` integrations
each declare `RequestParameters: { integration.request.header.X-User-Id:
context.authorizer.userId }` to bridge that gap — this is what lets Order
Service trust a `X-User-Id` header as "verified by the authorizer" instead
of trusting whatever a client puts in the request body.

**Testing without a real auth flow:** there's no Cognito/login endpoint in
this project, so `scripts/generate-test-jwt.sh` mints a valid HS256 JWT
using nothing but `openssl` (base64url-encode a header + payload, HMAC-SHA256
sign, base64url-encode the signature) — see the Testing Guide below. A unit
test (`JwtAuthorizerHandlerTest.tokenMintedByGenerateTestJwtShellScriptIsAccepted`)
actually runs that script and feeds its output through the handler, so the
two independent token-minting paths (openssl vs. jjwt) are proven to agree.

---

# Deep Dive — End-to-End Technical Reference

This section is the "explain it like a staff engineer would in a design doc"
version: a single timeline of one order request from click to archive
(including the async fanout and the failure path), then a component-by-component
breakdown of *why* each AWS service is there, then the exact IAM mechanics
of a real call in this codebase, then a focused look at Fargate/ECS and
Lambda internals. Written to double as certification (SAA-C03-level) and
interview prep — each section ends with the "gotcha" a staff engineer would
be expected to know.

## 1. The complete timeline (one order, start to finish)

Timestamps are illustrative, not measured — they show *relative order and
which parts are synchronous vs async*, which is the part that actually matters.

```
 SYNCHRONOUS PATH — client is waiting, blocks until 202/200/4xx returned
 ══════════════════════════════════════════════════════════════════════
 T+0ms     Client (mobile/web)
             │  POST /orders  Header: Authorization: Bearer <JWT>
             │                Header: X-Idempotency-Key: <client-generated UUID>
             ▼
 T+2ms     API Gateway
             │  TLS termination, request throttling, routes to authorizer
             ▼
 T+5ms     Lambda Authorizer  (custom REQUEST authorizer)
             │  verifies JWT signature + expiry, checks IAM policy claims
             │  ✗ invalid  → 401 Unauthorized, STOP. Nothing downstream runs.
             │  ✓ valid    → returns an IAM policy document "Allow: invoke API"
             ▼
 T+15ms    API Gateway → forwards original request to backend
             ▼
 T+18ms    Application Load Balancer (public subnet)
             │  routes :443 → target group → healthy ECS task on :8080
             ▼
 T+20ms    Order Service — ECS Fargate task (Spring Boot, private subnet)
             │  OrderController.createOrder()
             ▼
 T+22ms    IdempotencyService.claim(key, generatedOrderId)
             │  DynamoDB PutItem with ConditionExpression attribute_not_exists(idempotencyKey)
             │
             │  ✗ duplicate key (ConditionalCheckFailedException)
             │      → GetItem to fetch the original orderId
             │      → return cached OrderResponse, 202 Accepted, STOP (no new work)
             │
             │  ✓ new key claimed → continue
             ▼
 T+28ms    OrderRepository.save(order)
             │  RDS Aurora PostgreSQL — INSERT INTO orders (status='PENDING'), order_items
             ▼
 T+35ms    OrderQueuePublisher.publish(order)
             │  SQS SendMessage → orderflow-order-queue-{env}
             ▼
 T+38ms    Client receives 202 Accepted { orderId, status: PENDING }
             (client polls GET /orders/{id} afterwards for status changes)

 ── client's HTTP connection is closed here — everything below is async ──

 ASYNC FANOUT — nothing below blocks the client; failures here are retried
 ══════════════════════════════════════════════════════════════════════
 T+100ms   Lambda "order-processor" — SQS event source mapping polls the
           queue (long polling, batch size 10) and invokes the function
             │  deserialises message, validates payload
             ▼
 T+130ms   calls Inventory Service (ECS Fargate, gRPC/REST)
             │  reserves stock with an optimistic lock (version column) in RDS
             │
             │  ✗ out of stock → publish "OrderFailed" event → EventBridge
             │      (downstream: notify customer, no payment attempted)
             │
             │  ✓ stock reserved → continue
             ▼
 T+160ms   publish "OrderConfirmed" → EventBridge custom bus (orderflow-order-bus)
             │  three rules pattern-match on source=com.orderflow.order:
             │
             ├─▶ rule: payment     → Lambda "payment"
             │                        charge card, idempotent by orderId
             │                        UPDATE orders SET status='PAID' (or 'PAYMENT_FAILED')
             │
             ├─▶ rule: notify      → SNS topic "orderflow-notifications"
             │                        fan-out (SNS → SQS, RawMessageDelivery=true):
             │                          ├─ email queue → Lambda → send email
             │                          └─ sms queue   → Lambda → send SMS
             │
             └─▶ rule: archive     → Lambda "archiver" → S3 (JSON, full event,
                                      partitioned by date — audit trail / analytics)

 T+ any    CloudWatch — collects logs + metrics continuously from every
           component above (ECS Container Insights, Lambda metrics, SQS
           queue depth, RDS performance insights, ALB 5xx count)

 FAILURE & RECOVERY PATH — only triggers if the async processor keeps failing
 ══════════════════════════════════════════════════════════════════════
 attempt 1   order-processor Lambda throws (e.g. inventory service timeout)
             │  message is NOT deleted from the queue
             ▼
 +30s        SQS VisibilityTimeout (30s) expires → message becomes visible
             again → Lambda polls it a 2nd time → attempt 2 fails
             ▼
 +60s        attempt 3 fails → maxReceiveCount (3) reached
             ▼
 instantly   SQS automatically moves the message to the DLQ
             (orderflow-order-dlq — 14-day retention, no code involved)
             │
             │  order in RDS is still status='PENDING' — stuck until resolved
             ▼
 within 60s  CloudWatch Alarm (DLQ ApproximateNumberOfMessagesVisible > 0)
             fires → SNS "ops-alerts" topic → PagerDuty/email → on-call engineer
             ▼
             on-call inspects the message (Admin API / SQS console),
             fixes the root cause (e.g. inventory service was down),
             then triggers the redrive Lambda → patches payload if needed
             → resends to the main queue → normal processing resumes
```

**Gotcha (interview/exam):** the client only ever waits for the *synchronous*
path (~40ms). Everything from the SQS publish onward is decoupled — this is
the entire point of the architecture. A slow payment provider or a flaky
inventory service can never make `POST /orders` hang; it can only delay
when the order's status flips from `PENDING` to `PAID`.

## 2. What each service does and why it's there

| Service | Role in this system | Why this one and not an alternative |
|---|---|---|
| **VPC** | Private network boundary. 2 public + 2 private subnets across 2 AZs. | Public subnet = internet-facing (ALB only). Private subnet = ECS tasks + RDS, unreachable from the internet even if misconfigured, because there's no route to an Internet Gateway. |
| **Internet Gateway (IGW)** | Lets public subnets reach/be reached by the internet. | Required for inbound ALB traffic and for NAT's outbound path. |
| **NAT Gateway** | Lets *private*-subnet resources (ECS tasks) make *outbound* calls (pull from ECR, call AWS APIs) without being inbound-reachable. | One-way door: outbound yes, inbound no. This is what makes "private subnet" meaningfully private while still functional. |
| **Security Groups** | Stateful virtual firewalls, attached per-resource, referencing *each other* (not raw CIDRs) — RDS SG only allows the ECS SG, ECS SG only allows the ALB SG. | Least privilege at the network layer: even if IAM were misconfigured, RDS is still unreachable from anything except an ECS task. |
| **API Gateway** *(phase 2)* | Single public entry point: TLS termination, throttling, request validation, routes to the Lambda authorizer then the backend. | Offloads cross-cutting concerns (rate limiting, auth) from the Order Service so it only deals with business logic. |
| **Lambda Authorizer** *(phase 2)* | Verifies the JWT once per request at the edge, before any compute-heavy service runs. | Cheaper and faster to reject a bad token at the gateway than inside a running Fargate task. |
| **ECS (Fargate)** | Runs the Order Service and (later) Inventory Service containers. | See §4 for the full "why Fargate" discussion. |
| **ECR** | Private Docker registry for `order-service` and `inventory-service` images. Scans on push, keeps last 10 images. | ECS task definitions reference an ECR image URI; scanning catches known CVEs before a bad image ever runs. |
| **RDS Aurora PostgreSQL (Serverless v2)** | System of record for orders — durable, transactional, relational data (`orders`, `order_items`). | Orders need ACID guarantees (money, quantities) that a NoSQL store doesn't give you for free. Serverless v2 scales ACUs (0.5–4) with load instead of paying for a fixed instance 24/7. |
| **DynamoDB** | Idempotency table only — single-key lookups, no relations, no joins. | Wrong tool for orders (no ACID transactions across items), right tool for "has this exact key been seen before" at any scale with single-digit-millisecond latency. |
| **SQS (order queue + DLQ)** | Buffers the "please process this order" work item; absorbs bursts; the DLQ isolates poison messages after 3 failed attempts. | Decouples the fast synchronous write path from slower downstream processing. Back-pressure is handled by the queue, not by making the caller wait. |
| **EventBridge** | Central event bus for `OrderConfirmed`/`OrderFailed`; rules route to N consumers. | Adding a 4th consumer (say, a fraud-check service) later means adding one more rule — zero changes to the producer. SNS/SQS alone would require the producer to know about every consumer. |
| **SNS** | Fans one `OrderConfirmed` event out to independent Email and SMS channels (via SQS subscriptions). | Each channel gets its own queue, its own retry/DLQ, and processes at its own pace — a slow SMS provider can't back up email delivery. |
| **Lambda** (order-processor, payment, notification, archiver, redrive) | Short-lived, event-triggered compute — no server to keep running between orders. | These are bursty, small units of work (charge a card, send an email) — paying for an always-on ECS task for each would be wasteful. See §5. |
| **S3** | Durable, cheap, append-only archive of every order event (success and failure) — the audit trail. | Retention/compliance and post-mortem analysis need the raw event history, which a live database isn't designed to keep forever. |
| **CloudWatch** | Logs (from every ECS task and Lambda), metrics (queue depth, task CPU/mem, ALB errors), and alarms (DLQ depth, error rate). | The only way anyone finds out something broke before a customer complains. |
| **IAM** | Every principal (ECS task, Lambda function, API Gateway) gets its own role with only the permissions it needs. | See §3 — this is the part most people get wrong, and the part exams/interviews probe hardest. |
| **CloudFormation** | Every resource above is defined as a template, deployed in dependency order via `scripts/deploy.sh`. | Reproducible, diffable infrastructure — no manual console clicking that can't be recreated. |

## 3. IAM in practice — tracing one real call: `sqsClient.sendMessage(...)`

This is the part that's easy to get backwards, so let's trace it exactly as
it happens in `OrderQueuePublisher.java`.

### The two ECS roles (this trips almost everyone up first)

`infra/iam/roles.yaml` defines **two separate roles** for the same ECS task,
and they do completely different jobs:

| | `EcsTaskExecutionRole` (`orderflow-ecs-execution-dev`) | `EcsTaskRole` (`orderflow-ecs-task-dev`) |
|---|---|---|
| Who assumes it | The **ECS agent** (infrastructure), before your container even starts | **Your application code**, at runtime, inside the container |
| Used for | Pulling the image from ECR, writing the container's stdout/stderr to CloudWatch Logs | Calling `sqs:SendMessage`, `dynamodb:PutItem`, etc. from Java code |
| Managed policy | `AmazonECSTaskExecutionRolePolicy` (AWS-managed, ECR pull + logs) | None — custom inline policy scoped to `orderflow-*` resources only |
| If you got it wrong | Task fails to even *start* (can't pull image / can't create log stream) | Task starts fine, but `SqsClient.sendMessage()` throws `AccessDeniedException` at runtime |

In the task definition (to be written in `ecs-service.yaml`), these map to
two distinct fields: `ExecutionRoleArn` and `TaskRoleArn`. Confusing the two
is probably the single most common ECS IAM mistake — the exam loves this
distinction.

### The actual credential chain for `sendMessage()`

1. `ecs-service.yaml`'s task definition sets `TaskRoleArn: orderflow-ecs-task-dev`.
2. When Fargate launches the task, the ECS agent calls `sts:AssumeRole` on
   that ARN and gets back temporary credentials (access key, secret key,
   session token) valid for a few hours.
3. The agent exposes those credentials to the container via a local-only
   HTTP endpoint (`169.254.170.2`) and injects the URI as the
   `AWS_CONTAINER_CREDENTIALS_RELATIVE_URI` environment variable. **No
   credentials are ever baked into the image or passed as env vars by us** —
   this is what the VPC diagram note "no credentials in code, IAM task role
   only" refers to.
4. `AwsConfig.java` builds `SqsClient.builder().region(...).build()` with no
   explicit credentials provider — the SDK's default provider chain detects
   `AWS_CONTAINER_CREDENTIALS_RELATIVE_URI` and fetches those temporary
   creds automatically.
5. `OrderQueuePublisher.publish()` calls `sqsClient.sendMessage(...)`. The
   SDK signs the request (SigV4) with the temporary credentials.
6. SQS's own IAM check evaluates the caller's identity (the assumed
   `orderflow-ecs-task-dev` role) against its permissions: the inline
   `OrderServicePermissions` policy allows `sqs:SendMessage` on
   `arn:aws:sqs:*:{account}:orderflow-*` — the queue name matches, so it's
   **Allow**. Request succeeds.

If step 6 didn't match — e.g. someone renamed the queue outside the
`orderflow-*` convention — this exact call would fail with
`AccessDeniedException`, at runtime, with everything else (image, network,
code) working fine. That's the class of bug this least-privilege setup is
designed to make loud and immediate rather than a silent security hole.

### Lambda's version of the same thing

Lambda doesn't split "execution" vs "task" the way ECS does — there's only
one role (`LambdaExecutionRole` / `orderflow-lambda-dev`), attached directly
to the function. The Lambda runtime injects credentials the same
container-metadata-endpoint way, and that single role has to cover both
"infrastructure" needs (attaching an ENI in the VPC, via the AWS-managed
`AWSLambdaVPCAccessExecutionRole`) *and* "application" needs (`sqs:ReceiveMessage`,
`sns:Publish`, DynamoDB access) in one policy. The name "execution role" is
historical — functionally, for your code's SDK calls, it behaves like ECS's
*task* role.

## 4. Why Fargate? ECS concepts, precisely

**Fargate** is a *serverless compute engine for containers*: you give ECS a
container image + how much CPU/memory it needs, and AWS runs it on
infrastructure you never see, patch, or scale yourself. Contrast with the
**EC2 launch type**, where ECS still schedules your containers, but *you*
provision, patch, and scale the underlying EC2 instances the containers run
on.

| Concept | What it actually is |
|---|---|
| **Cluster** (`orderflow-cluster-dev`) | A logical namespace/grouping of tasks and services. With Fargate, the cluster owns *no* servers itself — it's just a boundary for organizing and monitoring what runs in it (Container Insights metrics are per-cluster). |
| **Task definition** | A blueprint (JSON/YAML), *not* a running thing: container image URI, CPU/memory, port mappings, env vars, and — critically — `ExecutionRoleArn` + `TaskRoleArn`. Versioned; each deploy registers a new revision. |
| **Task** | One running instance of a task definition — the actual container(s), scheduled onto Fargate capacity. |
| **Service** | Keeps a target *count* of tasks running (e.g. 2), replaces any that die, and registers/deregisters tasks with the ALB target group as they start/stop. Also owns rolling-deployment behaviour (new task definition revision → gradually swap old tasks for new). |
| **Capacity provider** | `FARGATE` (on-demand) vs `FARGATE_SPOT` (spare capacity, up to ~70% cheaper, can be reclaimed with a 2-minute warning). This repo uses `FARGATE_SPOT` for dev (`ecs-cluster.yaml`) — acceptable because a dev task restarting costs nothing but a few seconds of downtime; production would mix both. |

### Why Fargate over the alternatives, for this project

- **vs EC2 launch type:** no instances to patch, size, or right-size; you pay
  per task's actual CPU/memory reservation. For a learning project (and for
  most services that aren't running at extreme, sustained, predictable
  scale), the ops overhead of managing an EC2 fleet isn't worth it.
- **vs EKS:** EKS (Kubernetes) is more powerful and portable, but has real
  fixed overhead — a control plane to pay for, YAML/Helm complexity, its own
  networking model (CNI) on top of the VPC you already built. That's exactly
  why it's parked as a **Phase 4 migration exercise** here: valuable to learn
  once ECS/Fargate fundamentals are second nature, overkill to start with.
- **vs Lambda (for the Order Service itself):** the Order Service is a
  long-lived Spring Boot app maintaining a JPA/Hibernate connection pool to
  RDS and serving continuous HTTP traffic — that's a container's job. Lambda
  is reserved for short, bursty, event-triggered work (see §5).

**Gotcha:** "ECS" is the orchestrator (scheduling, service discovery, health
checks, deployments); "Fargate" is just *one* of two ways to supply the
actual compute underneath it. You can move this same task definition to the
EC2 launch type by changing one field — the orchestration logic (service,
task definition, ALB wiring) doesn't change.

## 5. Lambda, precisely

A Lambda function is code AWS runs **on demand**, in a sandboxed execution
environment, in response to a trigger — you're never billed for idle time,
and there's no server to keep patched.

- **Cold start vs warm start:** the first invocation after a period of
  inactivity has to initialize a new execution environment (download code,
  start the runtime, run static initializers) — this is the "cold start"
  latency. Subsequent invocations within the same environment's lifetime
  reuse it ("warm") and skip that cost. This matters for the authorizer
  (extra latency on every API call) more than for the async processors
  (a few hundred ms doesn't matter when nothing is blocking on it).
- **Timeout:** a hard ceiling per invocation (max 15 minutes). In this repo,
  the order-processor Lambda's timeout (25s) is deliberately set *below* the
  SQS queue's `VisibilityTimeout` (30s) — if the timeout and visibility
  timeout were equal or inverted, a message could become visible to a second
  poller *while the first invocation is still technically running*,
  producing duplicate processing.
- **Event source mapping (SQS trigger):** for SQS, Lambda isn't "pushed" to —
  AWS runs an internal poller that long-polls the queue on your behalf
  (batch size configurable, here effectively per-message for simplicity),
  and invokes your function synchronously with the batch. If the function
  throws, none of the messages in that failed batch are deleted — they
  become visible again after the visibility timeout and get retried, up to
  `maxReceiveCount` (3) before falling to the DLQ. (A production refinement
  worth knowing for the exam: returning `batchItemFailures` from the handler
  lets you retry *only* the messages that actually failed out of a batch,
  instead of the whole batch — noted in the original flow diagrams as
  "reportBatchItemFailures".)
- **EventBridge → Lambda:** this is a *push* model, not a poller — EventBridge
  invokes the Lambda asynchronously the moment a rule matches. Async
  invocations get their own automatic retry (2 retries by default) and can
  be configured with an on-failure destination (e.g., back to an SQS DLQ) —
  conceptually the same safety net as SQS's DLQ, just via a different
  mechanism because the trigger isn't a queue.
- **Execution role:** one role per function (or shared, as here with
  `orderflow-lambda-dev`), attached at creation time — no "assume role at
  invoke time" ceremony beyond what the Lambda service itself does
  automatically. `AWSLambdaVPCAccessExecutionRole` specifically grants
  permission to create/manage the ENI Lambda needs to reach into the VPC
  (required here because the payment Lambda needs to reach RDS, which lives
  in private subnets).

**Gotcha:** SQS-triggered Lambda is *pull* (Lambda service polls for you,
but conceptually the queue is the source of truth for what's pending);
EventBridge-triggered Lambda is *push* (invoked the instant a rule matches).
This distinction changes how you reason about retries and ordering in each
case, and it's a favorite "explain the difference" interview question.

## 6. Quick-reference cheat sheet (exam/interview recall)

| Question | Answer, from this repo |
|---|---|
| ECS execution role vs task role? | Execution = agent pulls image + writes logs. Task = your app's own AWS SDK calls. |
| Why 2 public + 2 private subnets? | Multi-AZ high availability — one subnet pair per AZ so an AZ outage doesn't take the app down. |
| Why does RDS's security group reference the ECS security group, not a CIDR? | Least privilege that survives IP changes — "traffic from this specific SG" instead of "traffic from this IP range." |
| SQS `VisibilityTimeout` vs Lambda timeout — which must be bigger? | VisibilityTimeout > Lambda timeout, always — otherwise a message can be redelivered while still being processed. |
| What deletes an SQS message? | The consumer, explicitly, after successful processing (or Lambda's event source mapping does it for you on a successful, non-throwing invocation). |
| DynamoDB TTL — does deletion happen immediately at expiry? | No — it's a background sweep, typically within 48 hours of the timestamp in the TTL attribute. Don't rely on it for anything time-precise. |
| Why DynamoDB for idempotency, not RDS? | Single-key conditional write at any scale with single-digit-ms latency; no need for relations or transactions across rows — RDS would be a slower, more contended way to do the same job. |
| Why EventBridge instead of just more SQS/SNS? | Content-based routing by event pattern to any number of consumers, added by *rule*, not by touching the producer. |
| SNS vs EventBridge — when would you pick SNS directly? | When you just need simple fan-out to a known, fixed set of subscribers (this repo's Email/SMS split) rather than pattern-matched routing to a growing set of independent services. |
| NAT Gateway vs Internet Gateway? | IGW: two-way, for public subnets. NAT: one-way (outbound only), lets private subnets reach the internet without being reachable from it. |
| Aurora Serverless v2 ACU? | 1 ACU ≈ 2 GiB RAM; this repo scales 0.5–4 ACUs automatically with load, billed per ACU-hour — near-zero cost when idle. |
| FARGATE vs FARGATE_SPOT? | Same compute, Spot is discounted spare capacity that can be reclaimed with a 2-minute warning — fine for dev, mixed with on-demand for prod. |
| Where do idempotency and inventory locking actually prevent double-processing? | Idempotency key (DynamoDB conditional write) stops a duplicate *request*; the inventory service's optimistic lock (version column in RDS) stops a race on *stock* between two different, legitimate orders. Different problems, different mechanisms. |

**Known dev-only shortcut worth flagging:** `dynamodb.yaml` currently sets
`PointInTimeRecoveryEnabled: false` (cost saving) even though the template's
own comment describes a 35-day recovery window — flip it to `true` before
this ever holds anything you can't afford to lose, i.e. before prod.

---

# Deployment Guide — Phase 1, step by step

Prerequisites already confirmed in this account: AWS CLI v2, a working
`orderflow` named profile (`aws sts get-caller-identity --profile orderflow`),
region `ap-south-1`, account `339495302685`, and an existing
`orderflow-cfn-artifacts-339495302685` S3 bucket. You also need Docker
Desktop **running** (not just installed) for step 5, and Maven + a JDK for
steps 5 and 8 (a newer local JDK is fine — Maven cross-compiles down to the
`--release 21` the poms specify).

Every stack below is deployed with `aws cloudformation deploy`, which is
idempotent — re-running the same command after a template edit only
updates what changed, and prints "No changes to deploy" if nothing did.
**Deploy in this exact order** — later stacks `!ImportValue` outputs from
earlier ones, so CloudFormation will simply fail with a clear "no export
named X" error if you skip ahead.

Set these once per terminal session (never commit them):

```bash
export AWS_PROFILE=orderflow
export AWS_REGION=ap-south-1
export ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export DB_MASTER_PASSWORD='PickAStrongPassword123!'   # >= 8 chars, RDS master password
export JWT_SECRET='pick-a-random-secret-string-at-least-32-characters-long'
cd /Users/utkarsh/Documents/java-workspace/microservices/2026-learning/aws-orderflow
```

### 1. VPC

```bash
aws cloudformation deploy --template-file infra/vpc/vpc.yaml \
  --stack-name orderflow-vpc-dev --parameter-overrides Environment=dev \
  --capabilities CAPABILITY_NAMED_IAM \
  --s3-bucket orderflow-cfn-artifacts-$ACCOUNT_ID
```
Creates the VPC, 2 public + 2 private subnets, IGW, NAT Gateway, route
tables, and the 3 security groups. Takes ~2 minutes (NAT Gateway is the
slow part).

### 2. IAM roles

```bash
aws cloudformation deploy --template-file infra/iam/roles.yaml \
  --stack-name orderflow-iam-dev --parameter-overrides Environment=dev \
  --capabilities CAPABILITY_NAMED_IAM \
  --s3-bucket orderflow-cfn-artifacts-$ACCOUNT_ID
```
Creates `EcsTaskExecutionRole`, `EcsTaskRole`, `LambdaExecutionRole`,
`AuthorizerLambdaRole`. `CAPABILITY_NAMED_IAM` is required any time a
template creates IAM roles with explicit names — it's your acknowledgment
that you reviewed what permissions you're granting.

### 3. RDS Aurora PostgreSQL

```bash
aws cloudformation deploy --template-file infra/data/rds.yaml \
  --stack-name orderflow-rds-dev --parameter-overrides Environment=dev \
  DBMasterPassword=$DB_MASTER_PASSWORD \
  --capabilities CAPABILITY_NAMED_IAM \
  --s3-bucket orderflow-cfn-artifacts-$ACCOUNT_ID
```
**This is the slow one — 10-15 minutes.** Aurora is provisioning a real
cluster + writer instance even at Serverless v2's minimum capacity. Get a
coffee; don't Ctrl-C.

### 4. DynamoDB

```bash
aws cloudformation deploy --template-file infra/data/dynamodb.yaml \
  --stack-name orderflow-dynamodb-dev --parameter-overrides Environment=dev \
  --capabilities CAPABILITY_NAMED_IAM \
  --s3-bucket orderflow-cfn-artifacts-$ACCOUNT_ID
```
Creates the idempotency table. Seconds, not minutes — DynamoDB tables are
fast to provision.

### 5. SQS, SNS, EventBridge

```bash
aws cloudformation deploy --template-file infra/messaging/sqs.yaml \
  --stack-name orderflow-sqs-dev --parameter-overrides Environment=dev \
  --capabilities CAPABILITY_NAMED_IAM --s3-bucket orderflow-cfn-artifacts-$ACCOUNT_ID

aws cloudformation deploy --template-file infra/messaging/sns.yaml \
  --stack-name orderflow-sns-dev --parameter-overrides Environment=dev \
  --capabilities CAPABILITY_NAMED_IAM --s3-bucket orderflow-cfn-artifacts-$ACCOUNT_ID

aws cloudformation deploy --template-file infra/messaging/eventbridge.yaml \
  --stack-name orderflow-eventbridge-dev --parameter-overrides Environment=dev \
  --capabilities CAPABILITY_NAMED_IAM --s3-bucket orderflow-cfn-artifacts-$ACCOUNT_ID
```
None of these are used by a *running* consumer yet (that's Phase 2) — but
`order-service` needs the order queue to exist so it has somewhere to
publish to.

### 6. ECR (create the repos, empty for now)

```bash
aws cloudformation deploy --template-file infra/compute/ecr.yaml \
  --stack-name orderflow-ecr-dev --parameter-overrides Environment=dev \
  --capabilities CAPABILITY_NAMED_IAM --s3-bucket orderflow-cfn-artifacts-$ACCOUNT_ID
```

### 7. Build & push the order-service Docker image

The ECS service you deploy in step 9 will fail to start tasks if this
image doesn't exist yet — do this before step 9, not after.

```bash
aws ecr get-login-password --region $AWS_REGION | \
  docker login --username AWS --password-stdin $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com

cd services/order-service
docker build -t orderflow/order-service-dev:latest .
docker tag orderflow/order-service-dev:latest \
  $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/orderflow/order-service-dev:latest
docker push $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/orderflow/order-service-dev:latest
cd ../..
```

### 8. ECS cluster

```bash
aws cloudformation deploy --template-file infra/compute/ecs-cluster.yaml \
  --stack-name orderflow-ecs-cluster-dev --parameter-overrides Environment=dev \
  --capabilities CAPABILITY_NAMED_IAM --s3-bucket orderflow-cfn-artifacts-$ACCOUNT_ID
```

### 9. ECS service (ALB + task definition + service)

```bash
aws cloudformation deploy --template-file infra/compute/ecs-service.yaml \
  --stack-name orderflow-ecs-service-dev --parameter-overrides Environment=dev \
  DBMasterPassword=$DB_MASTER_PASSWORD \
  --capabilities CAPABILITY_NAMED_IAM --s3-bucket orderflow-cfn-artifacts-$ACCOUNT_ID
```
CloudFormation won't mark this `CREATE_COMPLETE` until the ECS service
actually reaches a stable state (2/2 tasks healthy behind the ALB target
group) — if the image is broken or the task can't reach RDS, this step is
where you'll find out, usually within 5 minutes.

### 10. Package & upload the Lambda authorizer

```bash
cd services/lambda-authorizer
mvn -q clean package
cd ../..
aws s3 cp services/lambda-authorizer/target/lambda-authorizer.jar \
  s3://orderflow-cfn-artifacts-$ACCOUNT_ID/lambda-authorizer/lambda-authorizer.jar
```

### 11. API Gateway + authorizer wiring

```bash
aws cloudformation deploy --template-file infra/api/api-gateway.yaml \
  --stack-name orderflow-api-dev --parameter-overrides Environment=dev \
  JwtSecret=$JWT_SECRET \
  --capabilities CAPABILITY_NAMED_IAM --s3-bucket orderflow-cfn-artifacts-$ACCOUNT_ID
```

### 12. Get your API URL

```bash
export API_URL=$(aws cloudformation describe-stacks --stack-name orderflow-api-dev \
  --query "Stacks[0].Outputs[?OutputKey=='ApiInvokeUrl'].OutputValue" --output text)
echo $API_URL
```

That URL is Flow 1, fully deployed: `https://<api-id>.execute-api.ap-south-1.amazonaws.com/dev`.

**After the first manual pass**, `scripts/deploy.sh` runs steps 1-6 and
8-9 and 11 in one shot (`DB_MASTER_PASSWORD=... JWT_SECRET=... ./scripts/deploy.sh`)
— it does **not** do steps 7 or 10 (Docker/jar build+push aren't
CloudFormation's job), so build and push those first, same as above.

### Tearing down (avoid idle cost)

NAT Gateway (~$0.045/hr) and the Aurora Serverless v2 writer (billed per
ACU-hour even near-idle) are the two line items that actually cost
something meaningful while nothing's running. Delete in **reverse order**
— CloudFormation blocks deleting a stack whose exports another stack still
imports:

```bash
aws cloudformation delete-stack --stack-name orderflow-api-dev
aws cloudformation delete-stack --stack-name orderflow-ecs-service-dev
aws cloudformation delete-stack --stack-name orderflow-ecs-cluster-dev
aws cloudformation delete-stack --stack-name orderflow-ecr-dev
aws cloudformation delete-stack --stack-name orderflow-eventbridge-dev
aws cloudformation delete-stack --stack-name orderflow-sns-dev
aws cloudformation delete-stack --stack-name orderflow-sqs-dev
aws cloudformation delete-stack --stack-name orderflow-dynamodb-dev
aws cloudformation delete-stack --stack-name orderflow-rds-dev   # snapshot taken first (DeletionPolicy: Snapshot)
aws cloudformation delete-stack --stack-name orderflow-iam-dev
aws cloudformation delete-stack --stack-name orderflow-vpc-dev
```

---

# Testing Guide — validating everything built so far

This validates Flow 1 end-to-end (client → API Gateway → Lambda authorizer
→ Order Service → DynamoDB idempotency → RDS → SQS). It deliberately does
**not** test the async fanout (Flow 2) or DLQ path (Flow 3) — those
consumers (order-processor, payment, notification, archiver Lambdas)
don't exist until Phase 2/3. Messages will visibly pile up in
`orderflow-order-queue-dev` during this testing — that's expected, not a bug.

### 1. Confirm every stack is healthy

```bash
aws cloudformation describe-stacks \
  --query "Stacks[?starts_with(StackName,'orderflow')].{Name:StackName,Status:StackStatus}" \
  --output table
```
Every row should say `CREATE_COMPLETE` (or `UPDATE_COMPLETE` on a redeploy).

### 2. Confirm the ECS service is actually running tasks

```bash
aws ecs describe-services --cluster orderflow-cluster-dev \
  --services orderflow-order-service-dev \
  --query "services[0].{Desired:desiredCount,Running:runningCount,Pending:pendingCount}"
```
Expect `Desired: 2, Running: 2, Pending: 0`. If `Running` stays below
`Desired`, check logs (step 9 below) before going further — nothing past
here will work with an unhealthy service.

### 3. Reject a request with no token (expect 401)

```bash
curl -i -X POST $API_URL/orders \
  -H 'Content-Type: application/json' \
  -d '{"userId":"user-123","items":[{"productId":"p1","quantity":2,"unitPrice":9.99}]}'
```
Expect `HTTP/1.1 401 Unauthorized`. This confirms the authorizer is wired
in and rejecting by default — if you instead get a 5xx here, the
authorizer Lambda itself is misconfigured (check its permission/IAM role).

### 4. Mint a test JWT

```bash
TOKEN=$(JWT_SECRET=$JWT_SECRET ./scripts/generate-test-jwt.sh user-123)
echo $TOKEN
```

### 5. Reject a garbage token (expect 401)

```bash
curl -i -X POST $API_URL/orders -H 'Authorization: Bearer garbage' \
  -H 'Content-Type: application/json' -d '{}'
```
Expect 401 again — this time from `JwtAuthorizerHandler`'s signature check
failing, not a missing header.

### 6. Create an order with a valid token (expect 202)

```bash
IDEMPOTENCY_KEY=$(uuidgen)
curl -i -X POST $API_URL/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Idempotency-Key: $IDEMPOTENCY_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"userId":"user-123","items":[{"productId":"p1","quantity":2,"unitPrice":9.99}]}'
```
Expect `202 Accepted` with a JSON body containing `orderId` and
`status: "PENDING"`. Save the `orderId` for the next steps.

### 7. Replay the exact same request (idempotency check)

Re-run the **exact same curl command** from step 6 (same
`X-Idempotency-Key`). Expect the same `orderId` back, not a new one — this
is `IdempotencyService.claim()` rejecting the duplicate DynamoDB write and
returning the cached order instead of creating a second row in RDS.

### 8. Read the order back (expect 200, RDS round-trip)

```bash
ORDER_ID=<paste the orderId from step 6>
curl -i $API_URL/orders/$ORDER_ID -H "Authorization: Bearer $TOKEN"
```
Expect `200 OK`, `status: "PENDING"`. There's no direct RDS access needed
for this check — the GET endpoint reading a correct row back *is* the RDS
verification.

### 9. Confirm the DynamoDB idempotency record

```bash
aws dynamodb get-item --table-name orderflow-idempotency-dev \
  --key "{\"idempotencyKey\":{\"S\":\"$IDEMPOTENCY_KEY\"}}"
```
Expect an item with `orderId` matching step 6, and an `expiresAt` numeric
TTL attribute set ~24h in the future.

### 10. Confirm the message landed in SQS

```bash
QUEUE_URL=$(aws cloudformation describe-stacks --stack-name orderflow-sqs-dev \
  --query "Stacks[0].Outputs[?OutputKey=='OrderQueueUrl'].OutputValue" --output text)
aws sqs receive-message --queue-url $QUEUE_URL --wait-time-seconds 2
```
Expect a message body containing your `orderId` and items. It stays in the
queue after this (no consumer exists to delete it yet, and `receive-message`
alone doesn't delete anything) — safe to leave, 24h retention will expire
it, or `aws sqs purge-queue --queue-url $QUEUE_URL` to clear test messages.

### 11. Read the logs

```bash
aws logs tail /ecs/orderflow-order-service-dev --since 15m --follow
aws logs tail /aws/lambda/orderflow-authorizer-dev --since 15m
```
The first shows Spring Boot's structured JSON logs for every request
(including the `Published order ... to SQS order queue` line from
`OrderQueuePublisher`); the second shows nothing on success (the
authorizer only logs on unhandled errors) — silence there is a good sign.

### What "done" looks like for Phase 1

Steps 3, 5 reject correctly; steps 6-10 all produce consistent state across
API → RDS → DynamoDB → SQS for the same `orderId`. That's the entire
synchronous path from the timeline in the Deep Dive section, proven live
in AWS — **confirmed working** (order created, visible in RDS/DynamoDB/SQS).

---

# Phase 2 Plan — the async fanout (SQS consumer → Inventory → Payment)

Phase 1 gets an order into SQS and stops. Nothing consumes it yet. Phase 2
completes Flow 2 up through payment: **SQS → order-processor Lambda →
Inventory Service → EventBridge → Payment Lambda**, so an order actually
moves from `PENDING` to `PAID`/`PAYMENT_FAILED` without manual intervention.
(SNS notification and S3 archive stay Phase 3 — `NotificationRule` in
`eventbridge.yaml` is already `ENABLED`, but nothing consumes the
email/sms SQS queues it feeds yet, which is fine, they just accumulate.)

## New components

| Component | Type | Responsibility |
|---|---|---|
| `services/inventory-service` | Spring Boot, ECS Fargate (new service, own internal ALB) | Reserves stock via an optimistic-lock `UPDATE` (version column) on a new `stock` table — same Aurora cluster, new Flyway migration, no new RDS stack |
| `order-processor` Lambda | New, SQS-triggered (event source mapping) | Consumes the order queue, calls Inventory Service over the internal ALB, publishes `OrderConfirmed`/`OrderFailed` to the existing EventBridge bus |
| `payment` Lambda | New, EventBridge-triggered | Already has a disabled rule waiting (`PaymentRule` in `eventbridge.yaml`) — simulates a charge, connects directly to Aurora via JDBC to set `status = PAID` or `PAYMENT_FAILED` |

## Decisions locked in

1. **Inventory Service is a real internal microservice**, not logic folded into the Lambda — ECS Fargate behind an **internal** (private-subnet-only) ALB, matching the original Flow 2 diagram. `order-processor` Lambda gets VPC attachment to reach it.
2. **Payment Lambda talks to RDS directly via JDBC** (bundles the Postgres driver, VPC-attached), the same pattern Order Service already uses — not a callback into Order Service's API.

## What this actually requires, piece by piece

**New VPC wiring** (`vpc.yaml`) — three new security groups, since "which SG can talk to which SG" is the real access-control layer here, not IAM:
- `LambdaVpcSecurityGroup` — attached to both new Lambdas' ENIs (egress to the internal ALB and to RDS)
- `InventoryAlbSecurityGroup` — the internal ALB; ingress only from `LambdaVpcSecurityGroup` on its port
- `InventorySecurityGroup` — the Inventory Service tasks; ingress only from `InventoryAlbSecurityGroup`
- `RdsSecurityGroup` gets one more ingress rule: allow `LambdaVpcSecurityGroup` on 5432 (currently only `EcsSecurityGroup` is allowed — Payment Lambda's direct JDBC needs this too)

**New/changed IAM** (`roles.yaml`) — continuing the least-privilege split from the authorizer rather than reusing the catch-all `LambdaExecutionRole`:
- `OrderProcessorLambdaRole`: SQS receive/delete on the order queue, `events:PutEvents` on the order bus (currently missing entirely — nothing has this permission yet)
- `PaymentLambdaRole`: `AWSLambdaVPCAccessExecutionRole` only (JDBC needs network reachability, not an IAM action) — no SQS/SNS/DynamoDB grants, it touches none of them
- Drop `rds-data:ExecuteStatement` from the old shared role — that's the separate RDS **Data API** feature, which isn't enabled on this cluster and isn't what direct JDBC uses

**New infra stacks**:
- `infra/compute/inventory-service.yaml` — internal ALB + target group + task definition + ECS service, same shape as `ecs-service.yaml` but `Scheme: internal` and no public listener needed beyond the VPC
- `infra/compute/lambda-order-processor.yaml` — Lambda function + SQS event source mapping (`BatchSize`, and worth adding `ReportBatchItemFailures` so a bad message in a batch doesn't force-retry the whole batch)
- `infra/compute/lambda-payment.yaml` — Lambda function; flips `eventbridge.yaml`'s `PaymentRule` from `DISABLED` to `ENABLED` and adds the matching `AWS::Lambda::Permission` for EventBridge to invoke it

**New service code**:
- `services/inventory-service` — new Maven module, own `pom.xml`/Dockerfile/Flyway migration, one endpoint (`POST /inventory/reserve`)
- `services/order-processor-lambda` — new Lambda module (plain Java, same reasoning as the authorizer: short-lived, no Spring Boot)
- `services/payment-lambda` — new Lambda module, bundles `postgresql` JDBC driver

## Not doing yet (stays Phase 3)

Notification Lambdas (email/sms consumers of the already-provisioned SNS→SQS
fan-out), S3 archiver Lambda, DLQ redrive Lambda, `cloudwatch.yaml` alarms —
none of this blocks proving the payment path works.

---

## Phase 2 — BUILT (not yet deployed)

Everything below compiles and its tests pass locally; all templates pass
`aws cloudformation validate-template`, and every `ImportValue` was
cross-checked against a matching export.

### New services

| Module | Tests | Notes |
|---|---|---|
| `services/inventory-service` | 5 | Spring Boot / Fargate. Owns the `inventory` schema — **its own** `flyway_schema_history`, so it migrates the shared Aurora cluster independently of order-service's `public` schema. `POST /inventory/reserve`. |
| `services/order-processor-lambda` | 4 | Plain Java, SQS-triggered. Reserves stock, publishes `OrderConfirmed`/`OrderFailed`. |
| `services/payment-lambda` | 6 | Plain Java, EventBridge-triggered. JDBC to Aurora. No AWS SDK dependency at all. |

### New infra

- `infra/compute/inventory-service.yaml` — **internal** ALB (private IPs only, no internet route) + Fargate service
- `infra/compute/lambda-order-processor.yaml` — function + SQS event source mapping (`BatchSize: 10`, `ReportBatchItemFailures`)
- `infra/compute/lambda-payment.yaml` — function + the `AWS::Lambda::Permission` EventBridge needs to invoke it
- `vpc.yaml` — 3 new SGs forming the chain `Lambda → internal ALB → inventory tasks`, plus RDS ingress from the Lambda and inventory SGs
- `roles.yaml` — `OrderProcessorLambdaRole`, `PaymentLambdaRole`, `InventoryTaskRole`
- `eventbridge.yaml` — `PaymentRule` flipped `DISABLED` → `ENABLED`
- `deploy.sh` steps 15–17, `teardown.sh` updated to delete the new stacks first

### Three correctness decisions worth remembering

1. **Business rejection vs transient failure.** Out-of-stock returns 409 and
   the processor publishes `OrderFailed` and *deletes the message*. A 5xx or
   timeout throws, so the message is reported as a batch item failure and
   SQS retries it. Getting this backwards means real out-of-stock orders get
   retried 3× and land in the DLQ looking like an outage.
2. **Idempotency at every async hop.** SQS is at-least-once and EventBridge
   retries async invocations, so both consumers assume duplicate delivery:
   inventory-service keys on a `stock_reservations` row, and the payment
   Lambda uses a conditional `UPDATE ... WHERE status = 'PENDING'` (0 rows
   updated = already handled, skip).
3. **Network reachability ≠ IAM authorization.** `PaymentLambdaRole` has no
   custom policy whatsoever, because "run a SQL statement" isn't an IAM
   action — JDBC access is granted purely by the SG chain. Meanwhile the
   Phase 1 role's `rds-data:ExecuteStatement` was removed: that's the RDS
   Data API, which isn't enabled on this cluster and was granting nothing.

### Fixed along the way

- `order-service/pom.xml` and `inventory-service/pom.xml` now declare Lombok
  as an explicit `annotationProcessorPaths` entry. JDK 23+ stopped running
  classpath annotation processors implicitly, so a local `mvn compile` on a
  modern JDK failed with `cannot find symbol: getId()` even though the
  Docker build (JDK 21) succeeded.

### Deploying it

Same one command as Phase 1 — it now runs 18 steps instead of 15:

```bash
export DB_MASTER_PASSWORD="..." JWT_SECRET="..."
./scripts/deploy.sh
```

### What "done" looks like for Phase 2

Create an order, wait ~5s, `GET /orders/{id}` — status is **PAID**, not the
`PENDING` it was created as. The script prints commands for the two failure
paths too: an order over 10000 total → `PAYMENT_FAILED`; ordering more than
the seeded stock of `prod-003` → stays `PENDING` with an `OrderFailed` event
and no payment attempted.

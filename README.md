# flaglog

Feature-flag service with a built-in audit log. Clojure, Datomic, AWS Fargate.

The audit log isn't a separate table. Every flag value is just stored in Datomic, which keeps full history automatically. Each `PUT` records `actor` and `reason` on the transaction itself (`:tx/actor`, `:tx/reason`), so the audit metadata travels with the change. Point-in-time reads are `d/as-of`; the full timeline is `d/history`.

## API

| Method | Path                  |                                                 |
|--------|-----------------------|-------------------------------------------------|
| GET    | `/healthz`            | liveness                                        |
| GET    | `/flags`              | list all flags with current values              |
| PUT    | `/flags/:key`         | set or update; body: `value`, `actor`, `reason` |
| GET    | `/flags/:key`         | current value, or `?as-of=<ISO-8601>`           |
| GET    | `/flags/:key/history` | change timeline                                 |

```bash
curl -X PUT localhost:8080/flags/checkout.new \
  -H 'content-type: application/json' \
  -d '{"value":"true","actor":"alex","reason":"go live"}'

curl localhost:8080/flags/checkout.new/history
curl 'localhost:8080/flags/checkout.new?as-of=2026-05-14T17:00:00Z'
```

## Running

Java 21, Clojure CLI.

```bash
clj -M:test                                              # tests
clj -M:dev                                               # REPL: (go) (reset) (halt)
clj -T:build uber                                        # → target/flaglog.jar
PORT=8080 DATOMIC_URI=datomic:mem://flaglog \
  java -jar target/flaglog.jar
```

## Deploying

Terraform module under `infra/`:

```bash
cd infra
terraform init
terraform apply -var='image_tag=<sha>'
```

Provisions a VPC, ECR repo, ECS Fargate cluster + service, ALB, IAM roles, and a CloudWatch log group. The image must already be in ECR — `.github/workflows/ci.yml` builds it on every push to `main`; pushing to ECR is left for you to wire up once an AWS account is connected.

The deployment runs Datomic in dev/in-memory mode embedded in the peer process, so data resets on task restart. A real deployment would swap the URI for a Datomic transactor backed by DynamoDB.

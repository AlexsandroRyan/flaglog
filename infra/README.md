# infra

Terraform module that deploys flaglog to AWS ECS behind an ALB.

```bash
terraform init
terraform apply -var='image_tag=<sha>'

ALB=$(terraform output -raw alb_dns_name)
curl "http://$ALB/healthz"
```

## What it builds

VPC (2 AZs, public + private subnets, NAT gateway), ECR repo (immutable tags, scan-on-push, keep-last-10), ALB on `:80` with `/healthz` health check, ECS cluster + service (1 task), task-execution + task IAM roles, security groups (ALB open to the world, service open only to ALB), CloudWatch log group (14-day retention).

## Not here

HTTPS/ACM/Route53, autoscaling, and remote Terraform state are all easy to add but need account-specific resources, so they're left out of the reference module.

## Cost

NAT gateway is the line item that matters (~$32/month). `terraform destroy` to tear it all down.

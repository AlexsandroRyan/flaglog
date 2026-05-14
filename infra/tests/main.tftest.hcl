# Mock-provider test for the flaglog infra module.
#
# Runs the full Terraform plan against a synthetic AWS provider — no
# LocalStack, no AWS account. Verifies the module parses, types check,
# the resource graph is acyclic, and outputs reference real attributes.
#
# Real-AWS behaviour (quotas, naming collisions, IAM-policy semantics) is
# not in scope here.

mock_provider "aws" {
  # The module reads available AZs at plan time. Hand the mock a realistic
  # list so `slice(..., 0, 2)` succeeds.
  override_data {
    target = data.aws_availability_zones.available
    values = {
      names = ["us-east-1a", "us-east-1b", "us-east-1c"]
    }
  }

  # Mock the rendered IAM policy JSON so the IAM role resources accept it.
  override_data {
    target = data.aws_iam_policy_document.assume_ecs
    values = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"ecs-tasks.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}"
    }
  }
}

run "plan_with_minimal_vars" {
  command = plan

  variables {
    image_tag = "test-sha"
  }

  # Single ECS task is the documented demo posture.
  assert {
    condition     = aws_ecs_service.this.desired_count == 1
    error_message = "expected a single ECS task in the demo deployment"
  }

  # Health check must point at the app's /healthz endpoint.
  assert {
    condition     = aws_lb_target_group.this.health_check[0].path == "/healthz"
    error_message = "ALB health check path must be /healthz"
  }

  # ECR images must be immutable so the same tag can't silently overwrite.
  assert {
    condition     = aws_ecr_repository.this.image_tag_mutability == "IMMUTABLE"
    error_message = "ECR repository must use immutable tags"
  }

  # Service SG must only accept ingress from the ALB SG.
  assert {
    condition     = length(aws_security_group.service.ingress) == 1
    error_message = "service SG should have exactly one ingress rule (ALB only)"
  }

  # ECS task must never have a public IP.
  assert {
    condition     = aws_ecs_service.this.network_configuration[0].assign_public_ip == false
    error_message = "ECS task must run with no public IP"
  }

  # CloudWatch log retention must be bounded.
  assert {
    condition     = aws_cloudwatch_log_group.service.retention_in_days <= 30
    error_message = "log retention must be set and not unbounded"
  }
}

run "plan_with_custom_region" {
  command = plan

  variables {
    image_tag = "test-sha"
    region    = "eu-west-1"
    cpu       = 1024
    memory    = 2048
  }

  assert {
    condition     = aws_ecs_task_definition.this.cpu == "1024"
    error_message = "cpu variable must propagate to task definition"
  }

  assert {
    condition     = aws_ecs_task_definition.this.memory == "2048"
    error_message = "memory variable must propagate to task definition"
  }
}

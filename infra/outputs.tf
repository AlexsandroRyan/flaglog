output "alb_dns_name" {
  description = "Public DNS for the load balancer. curl this to hit /healthz, /flags, etc."
  value       = aws_lb.this.dns_name
}

output "ecr_repository_url" {
  description = "Push images to this URL. GitHub Actions publishes here on main."
  value       = aws_ecr_repository.this.repository_url
}

output "log_group" {
  description = "CloudWatch Logs group receiving the service's stdout."
  value       = aws_cloudwatch_log_group.service.name
}

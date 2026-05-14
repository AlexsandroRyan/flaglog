variable "region" {
  description = "AWS region to deploy into."
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Deployment environment label (used in tags + name prefixes)."
  type        = string
  default     = "demo"
}

variable "name" {
  description = "Service name. Used as the prefix for AWS resource names."
  type        = string
  default     = "flaglog"
}

variable "image_tag" {
  description = "Container image tag to deploy. Typically the git SHA."
  type        = string
}

variable "container_port" {
  description = "Port the application listens on inside the container."
  type        = number
  default     = 8080
}

variable "cpu" {
  description = "Fargate task CPU units."
  type        = number
  default     = 512
}

variable "memory" {
  description = "Fargate task memory in MiB."
  type        = number
  default     = 1024
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC."
  type        = string
  default     = "10.42.0.0/16"
}

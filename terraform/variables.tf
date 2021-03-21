variable "aws_account" {}

variable "aws_region" {}

variable "environment_name" {}

variable "service_name" {}

variable "vpc_name" {}

variable "ecs_task_iam_role_id" {}

# New Relic
variable "newrelic_agent_enabled" {
  default = "true"
}

locals {
  java_opts = [
    "-javaagent:/app/newrelic.jar",
    "-Dnewrelic.config.agent_enabled=${var.newrelic_agent_enabled}",
  ]

  service = element(split("-", var.service_name), 0)
  tier    = element(split("-", var.service_name), 1)
}

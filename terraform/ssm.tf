// AWS REGION

resource "aws_ssm_parameter" "upload_region" {
  name  = "/${var.environment_name}/${var.service_name}/upload-region"
  type  = "String"
  value = var.aws_region
}

// JWT

resource "aws_ssm_parameter" "upload_jwt_secret_key" {
  name      = "/${var.environment_name}/${var.service_name}/upload-jwt-secret-key"
  overwrite = false
  type      = "SecureString"
  value     = "dummy"

  lifecycle {
    ignore_changes = [value]
  }
}

// S3

resource "aws_ssm_parameter" "upload_bucket" {
  name  = "/${var.environment_name}/${var.service_name}/upload-bucket"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.uploads_bucket_id
}

// PENNSIEVE API

resource "aws_ssm_parameter" "pennsieve_api_base_url" {
  name  = "/${var.environment_name}/${var.service_name}/pennsieve-api-base-url"
  type  = "String"
  value = "https://${data.terraform_remote_state.api.outputs.internal_fqdn}"
}

// S3

resource "aws_ssm_parameter" "s3_connection_pool_queue_size" {
  name  = "/${var.environment_name}/${var.service_name}/s3-connection-pool-queue-size"
  type  = "String"
  value = "2048"
}

resource "aws_ssm_parameter" "complete_parallelism" {
  name  = "/${var.environment_name}/${var.service_name}/complete-parallelism"
  type  = "String"
  value = "64"
}

resource "aws_ssm_parameter" "preview_parallelism" {
  name  = "/${var.environment_name}/${var.service_name}/preview-parallelism"
  type  = "String"
  value = "64"
}

resource "aws_ssm_parameter" "preview_batch_write_parallelism" {
  name  = "/${var.environment_name}/${var.service_name}/preview-batch-write-parallelism"
  type  = "String"
  value = "10"
}

resource "aws_ssm_parameter" "preview_batch_write_max_retries" {
  name  = "/${var.environment_name}/${var.service_name}/preview-batch-write-max-retries"
  type  = "String"
  value = "5"
}

resource "aws_ssm_parameter" "status_list_parts_parallelism" {
  name  = "/${var.environment_name}/${var.service_name}/status-list-parts-parallelism"
  type  = "String"
  value = "100"
}

// CHUNK HASH CACHING

resource "aws_ssm_parameter" "chunk_hash_table_name" {
  name  = "/${var.environment_name}/${var.service_name}/chunk-hash-table-name"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.upload_chunk_hash_dynamodb_table_name
}

// PREVIEW CACHING

resource "aws_ssm_parameter" "preview_metadata_table_name" {
  name  = "/${var.environment_name}/${var.service_name}/preview-metadata-table-name"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.upload_preview_metadata_dynamodb_table_name
}

resource "aws_ssm_parameter" "preview_files_table_name" {
  name  = "/${var.environment_name}/${var.service_name}/preview-files-table-name"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.upload_preview_files_dynamodb_table_name
}

// NEW RELIC CONFIGURATION

resource "aws_ssm_parameter" "java_opts" {
  name  = "/${var.environment_name}/${var.service_name}/java-opts"
  type  = "String"
  value = join(" ", local.java_opts)
}

resource "aws_ssm_parameter" "new_relic_app_name" {
  name  = "/${var.environment_name}/${var.service_name}/new-relic-app-name"
  type  = "String"
  value = "${var.environment_name}-${var.service_name}"
}

resource "aws_ssm_parameter" "new_relic_labels" {
  name  = "/${var.environment_name}/${var.service_name}/new-relic-labels"
  type  = "String"
  value = "Environment:${var.environment_name};Service:${local.service};Tier:${local.tier}"
}

resource "aws_ssm_parameter" "new_relic_license_key" {
  name      = "/${var.environment_name}/${var.service_name}/new-relic-license-key"
  overwrite = false
  type      = "SecureString"
  value     = "dummy"

  lifecycle {
    ignore_changes = [value]
  }
}

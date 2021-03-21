.PHONY: run run-cached build-docker build-jar run-no-container run-no-container-cached

VERSION      ?= 0.1.0
JAVA         ?= java
ASSEMBLY_JAR ?= target/scala-2.12/upload-service-assembly-$(VERSION)-SNAPSHOT.jar

build-docker:
	sbt clean docker

build-jar:
	sbt clean assembly

run-no-container: build-jar run-no-container-cached

run-no-container-cached:
	ENVIRONMENT=local \
	CLOUDWRAP_ENVIRONMENT=local \
	AWS_REGION=us-east-1 \
	AWS_ACCESS_KEY_ID=$(AWS_ACCESS_KEY_ID) \
	AWS_SECRET_ACCESS_KEY=$(AWS_SECRET_ACCESS_KEY)\
	AWS_SESSION_TOKEN=$(AWS_SESSION_TOKEN) \
	UPLOAD_JWT_SECRET_KEY=$(UPLOAD_JWT_SECRET_KEY) \
	PENNSIEVE_API_BASE_URL=https://dev-api-use1.pennsieve.net \
	PREVIEW_PARALLELISM=4000 \
	PREVIEW_BATCH_WRITE_PARALLELISM=10 \
	PREVIEW_BATCH_WRITE_MAX_RETRIES=5 \
	PREVIEW_METADATA_TABLE_NAME=dev-upload-preview-metadata-table-use1 \
	PREVIEW_FILES_TABLE_NAME=dev-upload-preview-files-table-use1 \
	COMPLETE_PARALLELISM=4000 \
	STATUS_LIST_PARTS_PARALLELISM=100 \
	UPLOAD_BUCKET=pennsieve-dev-uploads-use1 \
	UPLOAD_REGION=us-east-1 \
	CHUNK_HASH_TABLE_NAME=dev-upload-hash-chunk-table-use1 \
	$(JAVA) -jar $(ASSEMBLY_JAR)

run-cached:
	docker-compose up --remove-orphans

run: build-docker
	docker-compose up --remove-orphans

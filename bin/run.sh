#!/bin/sh
ARTIFACT_TARGET_PATH=$1

if [ $CLOUDWRAP_ENVIRONMENT = "local" ]
then
  java -XX:MaxRAMPercentage=80 -jar $ARTIFACT_TARGET_PATH
else
  /usr/bin/java -XX:MaxRAMPercentage=80 $JAVA_OPTS -jar $ARTIFACT_TARGET_PATH
fi

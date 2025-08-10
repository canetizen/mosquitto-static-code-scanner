#!/bin/sh
set -e

# Author: canetizen
# Created on Mon Aug 11 2025
# Description: Build-and-run entrypoint script for a Java MQTT service container.
#              Installs protoc, generates Java classes from .proto files,
#              builds the Maven project, and launches the service JAR.

cd /app

# Install protoc if not present
apt-get update && apt-get install -y protobuf-compiler && rm -rf /var/lib/apt/lists/*

# Generate Java classes from proto
mkdir -p src/main/java
protoc --proto_path=/proto --java_out=src/main/java /proto/*.proto

# Build project
mvn -q package -DskipTests

# Run application
java -jar target/*-runner.jar

#!/bin/sh
set -e

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

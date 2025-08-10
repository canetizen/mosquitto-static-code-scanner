DC = docker compose

.PHONY: up up-d down restart logs ps pull up-% stop-% rm-% clean clean-targets clean-generated rebuild-protos

up:            ## Start all services in foreground
	$(DC) up

up-d:          ## Start all services in detached mode
	$(DC) up -d

down:          ## Stop and remove all services
	$(DC) down

restart:       ## Restart all services
	$(DC) down
	$(DC) up -d

logs:          ## Follow logs for all services
	$(DC) logs -f

ps:            ## Show status of all containers
	$(DC) ps

pull:          ## Pull base images
	$(DC) pull mosquitto
	$(DC) pull eclipse-temurin:24-jdk

# Run a single service (e.g., make up-viper-listener)
up-%:
	$(DC) up $*

# Stop a single service
stop-%:
	$(DC) stop $*

# Remove a single service container
rm-%:
	$(DC) rm -f $*

# Cleanup tasks
clean: clean-targets clean-generated clean-logs ## Remove build artifacts and generated proto sources

clean-targets:       ## Remove Maven target directories
	find . -name target -type d -prune -exec rm -rf {} +

clean-generated:     ## Remove generated protobuf sources inside applications
	rm -rf eagle-eye-broadcast/src/main/java/com/github/canetizen/proto || true
	rm -rf falcon-strike-command/src/main/java/com/github/canetizen/proto || true
	rm -rf viper-listener/src/main/java/com/github/canetizen/proto || true
	rm -rf raptor-listener/src/main/java/com/github/canetizen/proto || true
	rm -rf hawk-engagement-monitor/src/main/java/com/github/canetizen/proto || true

# Clear all application logs
clean-logs: ## Remove all log files from each application
	find . -type f -name "*.log" -exec rm -f {} +

rebuild-protos: clean-generated clean-logs ## Remove generated protobufs, logs, and rebuild services
	$(DC) up --build

build-scan: ## Build static-code-scanner JAR
	cd static-code-scanner && mvn -q -DskipTests package

run-scan: ## Run static-code-scanner against current directory
	java -jar static-code-scanner/target/static-code-scanner-1.0.0.jar .
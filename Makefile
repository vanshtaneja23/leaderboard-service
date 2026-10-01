IMAGE        ?= leaderboard-service:local
KIND_CLUSTER ?= leaderboard
NAMESPACE    ?= leaderboard
TARGET       ?= localhost:9090

.PHONY: help test build up down logs kind-deploy kind-forward kind-delete loadtest

help: ## List targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-14s %s\n", $$1, $$2}'

test: ## Run all tests (needs Docker for Testcontainers)
	./gradlew test

build: ## Build the container image
	docker build -t $(IMAGE) .

.env:
	cp .env.example .env

up: .env ## Start service + Postgres + Redis with docker compose and wait until ready
	docker compose up --build -d
	@echo "waiting for readiness..."
	@for i in $$(seq 1 60); do \
		curl -fs localhost:8080/actuator/health/readiness >/dev/null 2>&1 && { echo "ready: gRPC on localhost:9090, actuator on localhost:8080"; exit 0; }; \
		sleep 2; \
	done; echo "service did not become ready"; docker compose logs app | tail -50; exit 1

down: ## Stop the compose stack and delete its volumes
	docker compose down -v

logs: ## Follow the service logs
	docker compose logs -f app

kind-deploy: build ## Create a kind cluster (if needed), load the image, and deploy k8s/
	@kind get clusters 2>/dev/null | grep -qx $(KIND_CLUSTER) || kind create cluster --config k8s/kind-config.yaml
	kind load docker-image $(IMAGE) --name $(KIND_CLUSTER)
	kubectl apply -k k8s/
	kubectl -n $(NAMESPACE) rollout status statefulset/postgres --timeout=180s
	kubectl -n $(NAMESPACE) rollout status deployment/redis --timeout=180s
	kubectl -n $(NAMESPACE) rollout restart deployment/leaderboard-service
	kubectl -n $(NAMESPACE) rollout status deployment/leaderboard-service --timeout=300s
	kubectl -n $(NAMESPACE) get pods -o wide
	@echo "run 'make kind-forward' to reach the service on localhost:9090 / localhost:8080"

kind-forward: ## Port-forward the in-cluster service to localhost:9090 (gRPC) and :8080 (actuator)
	kubectl -n $(NAMESPACE) port-forward svc/leaderboard-service 9090:9090 8080:8080

kind-delete: ## Delete the kind cluster
	kind delete cluster --name $(KIND_CLUSTER)

loadtest: ## Run the ghz load test against TARGET (default localhost:9090; start the stack first with 'make up')
	./loadtest/run.sh $(TARGET)

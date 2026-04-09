.PHONY: build test clean infra-up infra-down app-up app-down up down seed health

build:
	mvn clean package -DskipTests

test:
	mvn test

clean:
	mvn clean

infra-up:
	cd infra && docker compose up -d

infra-down:
	cd infra && docker compose down

app-up: build
	cd infra && docker compose --profile app up -d --build

app-down:
	cd infra && docker compose --profile app down

up: infra-up app-up seed

down:
	cd infra && docker compose --profile app down

seed:
	./scripts/seed.sh

health:
	@curl -sf http://localhost:8081/actuator/health | jq -r '.status' 2>/dev/null || echo "identity: DOWN"
	@curl -sf http://localhost:8082/actuator/health | jq -r '.status' 2>/dev/null || echo "workflow: DOWN"
	@curl -sf http://localhost:8083/actuator/health | jq -r '.status' 2>/dev/null || echo "worker: DOWN"
	@curl -sf http://localhost:8084/actuator/health | jq -r '.status' 2>/dev/null || echo "audit: DOWN"

# Local Developer Setup

This guide covers how to get Atlas running locally for development and testing.

---

## Prerequisites

| Tool | Minimum Version | Notes |
|------|-----------------|-------|
| Java | 25 (LTS) | Virtual threads required |
| Maven | 3.9+ | Or use the `./mvnw` wrapper included in the repo |
| Docker | 24+ | |
| Docker Compose | 2.20+ | Compose v2 (`docker compose`, not `docker-compose`) |
| `jq` | Any | Used in demo scripts and Makefile helpers |
| `make` | Any | All common operations have Make targets |

Check your versions:

```bash
java --version
./mvnw --version
docker --version
docker compose version
```

---

## Clone the Repository

```bash
git clone https://github.com/OriolJT/atlas.git
cd atlas
```

---

## Project Structure

```
atlas/
├── pom.xml                   # Parent POM
├── common/                   # Shared JAR (no Spring Boot plugin)
├── identity-service/         # Port 8081
├── workflow-service/         # Port 8082
├── worker-service/           # Port 8083
├── audit-service/            # Port 8084
├── infra/
│   ├── docker-compose.yml
│   ├── grafana/              # Provisioned dashboards
│   ├── prometheus/           # prometheus.yml + alerts.yml
│   └── kafka/                # Topic creation scripts
├── docs/
├── scripts/
│   └── seed.sh               # Demo data seeding
└── examples/
    └── workflows/            # Demo workflow JSON definitions
```

---

## Start Infrastructure

Start only the infrastructure dependencies (Postgres, Kafka, Redis, Prometheus, Grafana, Tempo):

```bash
make infra-up
```

Wait for health checks to pass. Kafka (KRaft mode) typically takes 10–15 seconds. You can verify:

```bash
docker compose -f infra/docker-compose.yml ps
```

All infrastructure services should show `healthy`.

---

## Build the Project

Build all modules from the root:

```bash
./mvnw clean package -DskipTests
```

Or with tests (requires Docker for Testcontainers):

```bash
./mvnw clean package
```

The build produces a fat JAR for each service:
```
identity-service/target/identity-service.jar
workflow-service/target/workflow-service.jar
worker-service/target/worker-service.jar
audit-service/target/audit-service.jar
```

---

## Run Services

### Option A: Full stack via Docker Compose (recommended for demos)

```bash
make up
```

This builds images and starts all four services plus infrastructure. Use this for running the full demo.

### Option B: Services outside Docker (recommended for development)

With infrastructure already running via `make infra-up`, start each service in a separate terminal:

```bash
# Terminal 1
./mvnw -pl identity-service spring-boot:run

# Terminal 2
./mvnw -pl workflow-service spring-boot:run

# Terminal 3
./mvnw -pl worker-service spring-boot:run

# Terminal 4
./mvnw -pl audit-service spring-boot:run
```

Default configuration in `application.yml` assumes infrastructure on localhost with standard ports.

---

## Seed Demo Data

Once all services are healthy, run the seed script:

```bash
make seed
```

Or directly:

```bash
bash scripts/seed.sh
```

This creates the demo tenant, users, roles, and publishes both workflow definitions. The script is idempotent — safe to run multiple times.

---

## Run Tests

### Unit tests only (no Docker required)

```bash
./mvnw test -Punit
```

### Integration tests (requires Docker for Testcontainers)

```bash
./mvnw verify -Pintegration
```

### All tests

```bash
./mvnw verify
```

Testcontainers pulls images for PostgreSQL, Kafka, and Redis automatically on first run.

### Run tests for a single service

```bash
./mvnw -pl workflow-service verify
```

---

## Environment Configuration

Services use Spring profiles. The default profile is `local`. Override with:

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

Key configuration properties (with defaults for local profile):

| Property | Default | Notes |
|----------|---------|-------|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/atlas` | Shared DB |
| `spring.kafka.bootstrap-servers` | `localhost:9092` | |
| `spring.data.redis.host` | `localhost` | |
| `atlas.jwt.secret` | (fixed dev secret) | Change for production |
| `atlas.jwt.access-token-ttl` | `900` (15 min) | In seconds |

---

## Useful Make Targets

| Target | Description |
|--------|-------------|
| `make up` | Build and start full stack |
| `make down` | Stop all services and remove containers |
| `make infra-up` | Start only infrastructure (Postgres, Kafka, Redis, etc.) |
| `make infra-down` | Stop infrastructure |
| `make seed` | Run seed script |
| `make health` | Check health of all application services |
| `make logs` | Tail logs for all services |
| `make logs SERVICE=workflow-service` | Tail logs for a specific service |
| `make test` | Run all tests |
| `make reset` | Stop everything and wipe all volumes (clean slate) |
| `make build` | Maven build without tests |

---

## Service Ports

| Service | Port |
|---------|------|
| Identity Service | 8081 |
| Workflow Service | 8082 |
| Worker Service | 8083 |
| Audit Service | 8084 |
| PostgreSQL | 5432 |
| Kafka | 9092 |
| Redis | 6379 |
| Prometheus | 9090 |
| Grafana | 3000 |
| Tempo | 3200 |

---

## OpenAPI / Swagger

In the `local` profile, interactive API documentation is available at:

- Identity Service: [http://localhost:8081/swagger-ui.html](http://localhost:8081/swagger-ui.html)
- Workflow Service: [http://localhost:8082/swagger-ui.html](http://localhost:8082/swagger-ui.html)
- Audit Service: [http://localhost:8084/swagger-ui.html](http://localhost:8084/swagger-ui.html)

Machine-readable OpenAPI JSON at `/v3/api-docs` on each service.

---

## Common Issues

**Kafka not ready:** Kafka uses KRaft mode (no Zookeeper). On first start it may take 15–20 seconds to elect a controller. If services fail to start, wait and retry `make up`.

**Testcontainers requires Docker:** Integration tests start real containers. Docker must be running and the Docker socket must be accessible. On macOS with Docker Desktop, ensure it is running.

**Port conflicts:** If any of the default ports are in use on your machine, override them in `infra/docker-compose.yml` or via environment variables before running `make up`.

**Java version:** Atlas requires Java 25. The Maven build will fail with a clear error if the wrong version is on the path. Use `sdk env` (SDKMAN) or `.java-version` (jenv) to switch.

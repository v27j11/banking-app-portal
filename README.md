# 🏦 BankingPortal-API

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white" alt="Java 17"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.3.1-brightgreen?logo=springboot&logoColor=white" alt="Spring Boot"/>
  <img src="https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql&logoColor=white" alt="MySQL"/>
  <img src="https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white" alt="Redis"/>
  <img src="https://img.shields.io/badge/Kafka-3.7-231F20?logo=apachekafka&logoColor=white" alt="Kafka"/>
  <img src="https://img.shields.io/badge/build-Maven-C71A36?logo=apachemaven&logoColor=white" alt="Maven"/>
  <img src="https://img.shields.io/badge/license-MIT-blue" alt="License"/>
</p>

<p align="center">
  A Spring Boot banking backend extended with the reliability patterns that separate<br/>
  a demo banking API from one you'd actually trust with real money.
</p>

---

## 📋 Table of Contents

- [Why This Exists](#-why-this-exists)
- [Features](#-features)
- [Architecture](#-architecture)
- [Tech Stack](#-tech-stack)
- [Prerequisites](#-prerequisites)
- [Setup](#-setup)
- [Configuration Reference](#-configuration-reference-what-you-must-change)
- [Running the App](#-running-the-app)
- [Trying It Out](#-trying-it-out)
- [Observability](#-observability)
- [Testing](#-testing)
- [API Reference](#-api-reference)
- [Design Decisions](#-design-decisions-worth-discussing-in-an-interview)
- [Troubleshooting](#-troubleshooting)

---

## 🎯 Why This Exists

The base application (account management, PIN auth, deposits, withdrawals,
fund transfers) already worked. This project's focus is everything that
separates a *demo* banking API from one you'd actually trust with real
money: what happens when a client retries a payment request, what happens
under load, what happens when a notification service is slow, and how
you'd debug a production incident from logs alone at 2 AM.

## ✨ Features

| # | Feature | Problem It Solves |
|---|---|---|
| 🔒 | **Idempotency** (Redis + AOP) | A client retries a deposit after a timeout — without this, the money moves twice |
| 🚦 | **Rate limiting** (Redis + Lua) | Brute-force login attempts / abuse against auth and transaction endpoints |
| 📨 | **Kafka event-driven notifications** | A slow/down mail server should never slow down or fail a money-moving request |
| 📝 | **Structured JSON logging + request tracing** | "Which log lines belong to this one failed request?" across an async system |
| ⚡ | **N+1 query fix + indexing** | Transaction history was getting linearly slower per transaction before this |

## 🏗 Architecture
```
Client
│
▼
RequestTracingFilter      → assigns/propagates a request ID; logs one
│                          structured access-log line per request
▼
RateLimitFilter            → Redis-backed fixed-window limiter;
│                          fails OPEN if Redis is down (availability > strictness)
▼
JwtAuthenticationFilter    → existing auth, now also stamps account number
│                          into logging context (MDC) once authenticated
▼
AccountController
│
├── @Idempotent (AOP)    → Idempotency-Key header handling: replay cached
│                          response / reject conflicting retry / lock
│                          against concurrent duplicate requests
▼
AccountServiceImpl         → business logic; publishes a Spring
│                          ApplicationEvent after each transaction
▼
TransactionEventProducer   → @TransactionalEventListener(AFTER_COMMIT)
│                          → only publishes to Kafka if the DB transaction
│                          actually committed
▼
Kafka topic: banking-portal.transaction-events
│
▼
TransactionEventConsumer   → sends the customer notification email,
fully decoupled from the original request
```
## 🧰 Tech Stack

| Layer | Technology |
|---|---|
| Language / Framework | Java 17, Spring Boot 3.3.1 |
| Security | Spring Security, JWT |
| Persistence | Spring Data JPA, MySQL 8 |
| Caching / Locking / Rate limiting | Redis 7 |
| Messaging | Apache Kafka 3.7 (KRaft mode — no Zookeeper) |
| Observability | Micrometer, Prometheus, Logback (`logstash-logback-encoder`) |
| Testing | JUnit 5, Mockito, MockMvc |
| Build | Maven |

## ✅ Prerequisites

- **Java 17** — `java -version`
- **Maven** (or the bundled `./mvnw` if present)
- **Docker + Docker Compose** — for MySQL, Redis, Kafka

## 🛠 Setup

### 1. Start infrastructure

```bash
cd docker
docker compose up -d
```

Starts MySQL (`:3306`), Redis (`:6379`), Kafka in KRaft mode (`:9092`).

> **Known gap:** `docker-compose.yml` mounts `./mysql/init.sql`, which
> doesn't ship in this repo. Create an empty one before first run:
> ```bash
> mkdir -p docker/mysql && touch docker/mysql/init.sql
> ```

Verify everything came up:
```bash
docker compose ps
docker compose logs kafka --tail 20
```

### 2. Create the config file

```bash
cp src/main/resources/application.properties.sample src/main/resources/application.properties
```

### 3. Edit `application.properties` — see the exact fields below

## ⚙️ Configuration Reference — what you must change

Only two things in the sample file will actually break the app if left
as-is. Everything else works out of the box against the Docker services
from Step 1.

| Property | Sample value | What to change it to | Why |
|---|---|---|---|
| `jwt.secret` | `your-secret-key` | A real Base64 string decoding to **≥ 64 bytes** | Signing uses HS512, which requires a key this long. Generate one: `openssl rand -base64 64` (or PowerShell: `[Convert]::ToBase64String((1..64\|%{Get-Random -Max 256}))`) |
| `spring.datasource.username` / `password` | `root` / `root` | `bankinguser` / `bankingpass` | The official MySQL image only grants `root` access from inside the container. Your app runs on the host, so it connects as the gateway IP — `root` will be rejected with *"Host ... is not allowed to connect"*. `bankinguser` is created with `'%'` (any-host) access by `docker-compose.yml`. |

Optional — won't break startup, but limits functionality if left as placeholders:

| Property | If left as placeholder | Fix |
|---|---|---|
| `spring.mail.username` / `password` | Emails (OTP, notifications, statements) silently fail to send (logged, not fatal — mail is `@Async`) | Use a real SMTP account, e.g. a Gmail address + [App Password](https://myaccount.google.com/apppasswords) |
| `geo.api.key` | Login-notification emails show location as `"Unknown"` instead of a real city | Free key at [findip.net](https://findip.net) |

Everything else (Redis host/port, Kafka bootstrap servers, rate-limit
thresholds, actuator exposure) already matches the docker-compose defaults
— leave as-is unless you've changed the compose file.

## ▶️ Running the App

```bash
mvn clean install -DskipTests
mvn spring-boot:run
```

App runs on **`http://localhost:8180`**.

Health check:
```bash
curl http://localhost:8180/actuator/health
```

## 🧪 Trying It Out

**Register → Login → Create PIN → Deposit:**
```bash
curl -X POST localhost:8180/api/users/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Test User","email":"test@example.com","phoneNumber":"9999999999","password":"pass1234","address":"Test Address"}'

curl -X POST localhost:8180/api/users/login \
  -H "Content-Type: application/json" \
  -d '{"identifier":"test@example.com","password":"pass1234"}'
# → copy the returned token

curl -X POST localhost:8180/api/account/pin/create \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"password":"pass1234","pin":"1234"}'

curl -X POST localhost:8180/api/account/deposit \
  -H "Authorization: Bearer <token>" \
  -H "Idempotency-Key: demo-key-1" \
  -H "Content-Type: application/json" \
  -d '{"pin":"1234","amount":100}'
```

**Verify idempotency works** — repeat the exact same deposit call with the
same `Idempotency-Key`: the response is replayed from cache and the
balance does **not** move twice.

**Verify rate limiting works:**
```bash
for i in $(seq 1 15); do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8180/api/users/login -d '{}'
done
# → starts returning 429 after the configured limit (default 10/min)
```

**Watch Kafka events live:**
```bash
docker exec -it bankingportal-kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic banking-portal.transaction-events --from-beginning
```

## 📊 Observability

| What | How |
|---|---|
| Structured JSON logs | `mvn spring-boot:run -Dspring-boot.run.profiles=json-logs` |
| Prometheus metrics | `GET /actuator/prometheus` |
| Health (detailed) | Set `management.endpoint.health.show-details=always`, then `GET /actuator/health` |

## 🧪 Testing

```bash
mvn test
```

| Test class | Coverage |
|---|---|
| `IdempotencyAspectTests` | 7 cases — no header, first request, duplicate same/different payload, concurrent in-flight, lock failure, exception-releases-lock |
| `RateLimitFilterTests` | 8 cases — bucket classification, limit enforcement, Redis-unavailable fail-open |
| `TransactionEventProducerTests` / `TransactionEventConsumerTests` | Kafka publish/consume, broker-down and account-not-found edge cases |
| `CacheServiceTests` | Redis ops including atomic `putIfAbsent` locking |
| Pre-existing suite | Account, auth, OTP, transaction flows — unmodified and still passing |

## 📡 API Reference

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/users/register` | |
| `POST` | `/api/users/login` | |
| `POST` | `/api/account/pin/create` | |
| `POST` | `/api/account/deposit` | Accepts optional `Idempotency-Key` header |
| `POST` | `/api/account/withdraw` | Accepts optional `Idempotency-Key` header |
| `POST` | `/api/account/fund-transfer` | Accepts optional `Idempotency-Key` header |
| `GET` | `/api/account/transactions` | Full history |
| `GET` | `/api/account/transactions/page?page=&size=` | Paginated history |
| `GET` | `/actuator/health` / `/actuator/prometheus` | Ops endpoints |

## 💡 Design Decisions Worth Discussing in an Interview

**Why AOP for idempotency instead of a filter or per-endpoint code?**
`@Idempotent` + `IdempotencyAspect` centralizes the lock/replay logic in
one place, applied declaratively wherever needed. A prior implementation
used a broken `@Cacheable` pointed at an unregistered cache name — found
during a codebase audit and replaced with this correct version.

**Why release the idempotency lock on failure?**
If the operation throws (e.g. insufficient balance), the Redis lock is
deleted immediately rather than left to expire — the client can retry with
the same key once the actual problem is fixed, instead of waiting out a
stale lock's full TTL.

**Why publish Kafka events `AFTER_COMMIT`, not inline in the service?**
`@TransactionalEventListener(phase = AFTER_COMMIT)` guarantees the Kafka
publish only fires once the DB transaction has durably committed — a
rolled-back deposit never triggers a customer notification.

**Why fail OPEN on rate limiting, not closed?**
A Redis outage shouldn't take down the entire banking API. The failure is
logged (alertable) but the request proceeds — availability of banking
operations wins over strict enforcement in that specific failure mode.

**Why `JOIN FETCH` instead of just an index?**
The index fixes lookup cost; `sourceAccount`/`targetAccount` are
`FetchType.LAZY`, so mapping N transactions to DTOs would still trigger up
to 2N extra queries without the fetch join. One query, regardless of
history size.

## 🔧 Troubleshooting

| Symptom | Fix |
|---|---|
| `Host '...' is not allowed to connect to this MySQL server` | Use `bankinguser`/`bankingpass`, not `root` — see [Configuration Reference](#-configuration-reference-what-you-must-change) |
| JWT decode error at startup | `jwt.secret` isn't valid Base64 or decodes to <64 bytes — regenerate with `openssl rand -base64 64` |
| `manifest unknown` on `docker compose up` for Kafka | Use `apache/kafka:3.7.0`, not `bitnami/kafka` (Bitnami removed free versioned tags in Aug 2025) |
| Login location always shows "Unknown" | `geo.api.key` is still the placeholder — harmless, just cosmetic; get a free key at findip.net if you want it fixed |
| `/actuator/health` shows `DOWN` | Set `management.endpoint.health.show-details=always` and re-check — it'll show which component (DB/Redis/Kafka) is actually failing |

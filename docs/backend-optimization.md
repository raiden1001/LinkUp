# Backend Optimization Additions

This document covers only the proposed backend hardening additions:

1. Shared DB module
2. Connection pooling with HikariCP
3. Transaction boundaries for multi-step writes

---

## 1) Shared DB Module

### Why

- Centralizes database config and behavior across services.
- Removes repeated JDBC setup logic in each service.
- Ensures consistent timeout, retry, and SSL settings.
- Makes maintenance safer by changing DB logic in one place.

### What to include

- Single DB config loader (`DB_URL`, `DB_USER`, `DB_PASSWORD`, pool settings).
- Shared connection factory/pool accessor.
- Common helpers for query execution and structured DB error mapping.
- Standard logging and metrics hooks for SQL timings/errors.

### Expected benefit

- Lower code duplication.
- Fewer config drifts between services.
- Faster incident debugging.

---

## 2) Connection Pooling (HikariCP)

### Why

- Opening new JDBC connections per request is costly.
- Reusing connections reduces latency and improves throughput.
- Pool limits protect PostgreSQL from connection spikes.
- Better handling of stale/broken connections under load.

### What to configure

- `maximumPoolSize` (based on service concurrency + DB capacity).
- `minimumIdle`, `connectionTimeout`, `idleTimeout`, `maxLifetime`.
- Validation query/health checks.
- Separate pool names per service for observability.

### Expected benefit

- Lower response time on DB-backed endpoints.
- More stable behavior during traffic bursts.
- Reduced risk of “too many connections” failures.

---

## 3) Transaction Boundaries

### Why

Multi-step operations should be all-or-nothing to avoid partial data writes.

### Where to apply first

- **Auth login flow**
  - user upsert
  - session insert
  - login audit insert
- **Conversation creation**
  - conversation insert
  - member inserts
- **Message update/status flows**
  - message update
  - dependent status/metadata writes

### Transaction policy

- Start transaction for each multi-write business operation.
- Commit only when all steps succeed.
- Roll back on any exception.
- Keep transactions short to reduce lock contention.

### Expected benefit

- Stronger data integrity.
- Fewer hard-to-reconcile partial states.
- Safer recovery behavior during failures.

---

## Suggested Rollout Order

1. Introduce shared DB module.
2. Switch services to HikariCP pool from the shared module.
3. Add transaction wrappers to targeted multi-step flows.
4. Add metrics/alerts for pool saturation and transaction failures.

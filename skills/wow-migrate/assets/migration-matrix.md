# Wow Breaking Migration and Data Cutover Matrix

## Baseline and target

| Item | Current evidence | Pinned target evidence | Status |
|---|---|---|---|
| Wow / Spring Boot / Java / Kotlin / KSP | | | |
| Modules and generated contracts | | | |
| Resolved dependency graph and runtimeClasspath | | | |
| Runtime and custom extensions | | | |
| Stores, buses and deployment topology | | | |

## Migration items

| Scope | Current contract | Target contract | Required action | Verification | Owner | Rollback impact | Status |
|---|---|---|---|---|---|---|---|
| | | | | | | | |

Use `MISSING EVIDENCE` for any field that cannot be proved. Do not replace it with an assumption.

## Runtime and Transport gate

| Check | Original result | Isolation or temporary change | Final evidence/status |
|---|---|---|---|
| Runtime preflight: logs, ports, JMX, JVM options | | | |
| Critical runtime classes and runtimeClasspath gaps | | | |
| Application startup and readiness | | | |
| Actual messaging/transport publish-consume or request-response path | | | |
| Health, liveness and readiness HTTP | | | |
| OpenAPI metadata, version, path count, critical paths and Swagger UI | | | |
| Wow-generated endpoint | | | |
| Application Controller endpoint | | | |
| Read-only query | | | |
| Missing/invalid query, path and body binding | | | |
| Graceful shutdown | | | |

Temporary isolation arguments, profiles, configuration, and dependencies must be listed explicitly, kept out of the committed diff, and never presented as the production result.

Mark an HTTP row `NOT APPLICABLE` only when scoped dependency/configuration evidence proves that capability is absent. Do not add optional runtime dependencies solely to fill this table; record the actual transport evidence instead.

## Cutover gates

| Gate | Evidence | Result |
|---|---|---|
| Writers drained | | |
| Backup restored in rehearsal | | |
| Migration dry-run/resume/checksum | | |
| Full reconciliation | | |
| Isolated target validation | | |
| Rollback rehearsal | | |

## Completion status

| Status | Evidence | Result |
|---|---|---|
| Migration code complete | | |
| Local runtime passed | | |
| Deployable | | |
| Production ready | | |

List external services, data migration/reconciliation, cutover, rollback, production configuration/secrets, and observability separately. No row inherits success from another row.

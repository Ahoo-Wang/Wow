---
title: Event Compensation Example
description: Run the compensation service and verify its Dashboard, management endpoints, notifications, and deployment recovery loop.
outline: deep
---

# Event Compensation Example

[`compensation`](https://github.com/Ahoo-Wang/Wow/tree/main/compensation) is a runnable Wow application and operator console. This page owns runtime and operator verification. See [Event Compensation](../../guide/event/compensation.md) for immediate retry, the `ExecutionFailed` state machine, and replay semantics, and [Compensation Configuration](../config/compensation.md) for complete properties.

## Modules and Verification Baseline

| Module | Runtime responsibility |
| --- | --- |
| `wow-compensation-api` | Command, event, state, and query contracts |
| `wow-compensation-domain` | `ExecutionFailed` aggregate and backoff calculation |
| `wow-compensation-core` | Failure capture, result write-back, and source-event replay |
| `wow-compensation-server` | Snapshot query, scheduling, OpenAPI, notification, and Dashboard hosting when a frontend build is present |
| `dashboard` | Failure queues, details, and operator actions |

Check the domain, core, and console first:

```bash
./gradlew :wow-compensation-domain:check :wow-compensation-core:check
pnpm --dir compensation/dashboard exec vitest run
```

`ExecutionFailedSpec` covers prepare, force prepare, success, another failure, and retry-specification changes. `CompensationFilterTest` covers filter error boundaries, while Dashboard tests cover queue conditions and action state. Successful commands prove only these local gates, not real messaging, storage, notifications, or a deployment environment.

## Local Service Startup, Health, and Route Check

The current default JVM arguments for `:wow-compensation-server:run` enable JMX on port 5555 without authentication or TLS. For the smallest safe local route check, build the distribution and use plain `java` bound only to loopback. `installDist` copies an existing `compensation/dashboard/dist`; it does not build the frontend, so this flow does not verify Dashboard assets when that output is absent.

```bash
./gradlew :wow-compensation-server:installDist

SERVER_PORT=18083 \
SERVER_ADDRESS=127.0.0.1 \
SPRING_AUTOCONFIGURE_EXCLUDE='org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration,org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration,org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration,org.springframework.boot.mongodb.autoconfigure.MongoReactiveAutoConfiguration' \
COSID_MACHINE_DISTRIBUTOR_TYPE=manual \
COSID_MACHINE_DISTRIBUTOR_MANUAL_MACHINE_ID=1 \
WOW_COMPENSATION_SCHEDULER_ENABLED=false \
WOW_COMPENSATION_WEBHOOK_WEIXIN_URL=false \
WOW_KAFKA_ENABLED=false \
WOW_COMMAND_BUS_TYPE=in_memory \
WOW_EVENT_BUS_TYPE=in_memory \
WOW_EVENTSOURCING_STATE_BUS_TYPE=in_memory \
WOW_EVENTSOURCING_STORE_STORAGE=in_memory \
WOW_EVENTSOURCING_SNAPSHOT_STORAGE=in_memory \
WOW_PREPARE_ENABLED=false \
WOW_MONGO_ENABLED=false \
WOW_REDIS_ENABLED=false \
WOW_ELASTICSEARCH_ENABLED=false \
java \
  -Dspring.config.location=file:compensation/wow-compensation-server/src/main/resources/application.yaml \
  -cp 'compensation/wow-compensation-server/build/install/wow-compensation-server/lib/*' \
  me.ahoo.wow.compensation.server.CompensationServerKt
```

Expect `Netty started on port 18083` and `Started CompensationServerKt`. Verify the same address and port from another terminal:

```bash
curl -fsS http://127.0.0.1:18083/actuator/health/liveness
curl -fsS http://127.0.0.1:18083/v3/api-docs | \
  jq -r '.paths["/execution_failed/{id}/prepare_compensation"].put.operationId'
```

Expect `{"status":"UP"}` and `compensation.execution_failed.prepare_compensation`. These checks prove only service startup, the health endpoint, and presence of the prepare route. They do not request Dashboard assets, send a compensation command, or execute a state transition, so they do not verify the Dashboard or local state machine. This mode also loses data when the process exits and disables automatic scheduling; it is not durable-recovery evidence.

Run and verify the Dashboard separately:

```bash
pnpm --dir compensation/dashboard dev
```

## Dashboard

The current Dashboard supplies these queues:

| Queue | Condition |
| --- | --- |
| **To Retry** | `RECOVERABLE` / `UNKNOWN` records below the limit that are `FAILED` or timed-out `PREPARED` |
| **Executing** | `PREPARED` records that have not timed out |
| **Next Retry** | Automatic-scheduling candidates whose `nextRetryAt` is due |
| **Non Retryable** | Active records at the ordinary retry limit |
| **Succeeded** | `SUCCEEDED` history |
| **Unrecoverable** | Active `UNRECOVERABLE` records |

The list supports exact filters by execution ID, event ID, aggregate ID, aggregate context/name, and processor context/name. Details show error and stack trace, event and aggregate identity, tenant, function, recoverability, RetrySpec, timing, state, and paginated event-stream history.

Available actions are:

- **Prepare compensation**: ordinary preparation, subject to state, timeout, and retry limit;
- **Force prepare**: after confirmation, cross the retry limit but not success or an unexpired `PREPARED`;
- **Apply retry spec**: change non-negative `maxRetries`, `minBackoff`, and `executionTimeout`;
- **Mark recoverable**: change recoverability and therefore automatic-scheduling eligibility;
- **Change function**: change context, processor, function name, and `EVENT` / `STATE_EVENT` kind.

The current UI has no delete or deleted-aggregate recovery button and defines no operator role model, approval flow, or audit-retention policy. A deployment must supply those controls through network, authentication, authorization, and audit layers.

![Event-Compensation-Dashboard](/images/compensation/dashboard.png)

![Event-Compensation-Dashboard-Apply-Retry-Spec](/images/compensation/dashboard-apply-retry-spec.png)

![Event-Compensation-Dashboard-Succeeded](/images/compensation/dashboard-succeeded.png)

![Event-Compensation-Dashboard-Error](/images/compensation/dashboard-error.png)

## Management Endpoints

The generated Dashboard client currently uses an empty `basePath`, so its default command routes are:

| Action | Route |
| --- | --- |
| Ordinary prepare | `PUT /execution_failed/{id}/prepare_compensation` |
| Force prepare | `PUT /execution_failed/{id}/force_prepare_compensation` |
| Change retry specification | `PUT /execution_failed/{id}/apply_retry_spec` |
| Change recoverability | `PUT /execution_failed/{id}/mark_recoverable` |
| Change target function | `PUT /execution_failed/{id}/change_function` |

An API Gateway may add an external context prefix; the running instance's OpenAPI is the final route evidence. The generated client also contains default aggregate delete and recovery routes, but the current Dashboard does not call them.

Prepare an existing retryable record:

```bash
curl -X PUT \
  'http://127.0.0.1:18083/execution_failed/<execution-id>/prepare_compensation' \
  -H 'Command-Wait-Stage: PROCESSED' \
  -H 'Command-Request-Id: prepare-<execution-id>'
```

`succeeded=true` and `stage=PROCESSED` prove only that the prepare command was handled. A later read may still see the old `FAILED`, a brief `PREPARED`, or final `SUCCEEDED` / new `FAILED`. To observe the complete result, poll snapshot/event queries and inspect state-event history instead of asserting one immediate read.

Verify failure paths too. Ordinary prepare rejects `SUCCEEDED`, an unexpired `PREPARED`, and a record at the limit. Force prepare still rejects success and an unexpired state. Applying success/failure directly to a non-`PREPARED` record returns `ExecutionFailed is not prepared.` Dashboard button state is guidance; the server state machine is authoritative.

## Notification Verification

After configuring WeCom, use controlled failure and success events to verify bot delivery, quick-navigation links, and sensitive-data boundaries. Successful WebHook delivery proves notification reachability only; reconcile authoritative state in the Dashboard or query result.

| Failure notification | Success notification |
| --- | --- |
| ![Execution Failed](/images/compensation/execution-failed.png) | ![Execution Succeeded](/images/compensation/execution-success.png) |

## Durable Deployment and Verification

For a durable environment, keep the distribution's direct `java` startup path, configure real MongoDB, Redis, Kafka, scheduler, and notification infrastructure, and remove the local example's in-memory/disable overrides. The repository supplies the service host and Dashboard build, not a production-ready cluster policy.

The smallest Kubernetes shape is below. An actual release and capacity check must determine the image digest, resources, replica count, and Secret names:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: compensation-service
spec:
  replicas: 2
  selector:
    matchLabels:
      app: compensation-service
  template:
    metadata:
      labels:
        app: compensation-service
    spec:
      containers:
        - name: compensation-service
          image: <registry>/wow-compensation-server@sha256:<digest>
          envFrom:
            - secretRef:
                name: wow-compensation-secrets
          ports:
            - name: http
              containerPort: 8080
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: http
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: http
```

Deployment verification should include at least:

1. pin an immutable image digest built from the selected Wow tag and use the same digest in test and production;
2. inject messaging, storage, notification, and authentication credentials through Secrets;
3. verify EventStore and SnapshotStore indexes, capacity, backup, and restore;
4. verify readiness/liveness, scheduler mutex behavior, backlog, failure age, restart counts, and error logs;
5. restrict the Dashboard and management endpoints to a protected operator network with TLS, authentication, fine-grained authorization, and audit;
6. exercise normal, retryable, unrecoverable, idempotent, and operator-recovery paths in test before promoting the same image.

`replicas: 2` does not prove high availability. Multiple replicas still depend on real failure verification of messaging, storage, and scheduler mutual exclusion.

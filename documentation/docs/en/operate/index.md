---
title: Running Wow
description: What running a Wow service takes, and a checklist to go through before production.
---

# Running Wow

A Wow service is a Spring Boot application that runs as one or more nodes. Its nodes share the service's database, find each other through it, split its log's partitions among themselves, and talk on an internal port. Services of one system talk over Kafka. This section is for whoever deploys and runs them.

## The pages

| Page | Covers |
| --- | --- |
| [Deployment Model](./topology) | Services and nodes, the two ports, what a service needs, starting and stopping |
| [Configure a Service](./configuration) | How configuration takes effect, what must match across nodes, development defaults |
| [PostgreSQL](./postgresql) | The tables, the schema, the connection pools, several databases |
| [Kafka](./kafka) | When a service needs Kafka, its topics, durable command intake |
| [Secure a Deployment](./security) | Callers, authorization, the internal port, personal data, tenants |
| [The Ops Service and Console](./ops-console) | The parked aggregates of every service in one place |

## Before production

**Storage**

- The service runs on a durable backend: the application gives a `DataSource` or a `MongoClient`, and no production node runs in a development profile.
- `wow.log.partitions` is chosen before the first deployment; it cannot change once the log holds entries.
- The database account matches `wow.ddl`: one that may create tables with `auto`, or the schema applied beforehand and `validate`.
- The log, checkpoints, leases, failure ledger, reservations and timers are backed up together.

**The cluster**

- Every node of a service has the same `wow.service`, partitions, stores and cluster tokens.
- Nodes reach each other's internal port, and nothing outside the cluster does.
- The platform's termination grace period covers the node's shutdown ([Deployment Model](./topology#starting-and-stopping)).
- Readiness and liveness probes read the node's health groups.

**Between services**

- Each Kafka topic the service owns exists, with the partitions it needs: a command topic has exactly as many as the context's log ([Kafka](./kafka#topics)).
- Each service lists the services it trusts on its internal port, and the commands each may call.

**Security**

- Go through the checklist of [Secure a Deployment](./security#checklist).

**Operations**

- The ops service collects every service's failure ledger, and someone watches it ([The Ops Service and Console](./ops-console)).
- The configuration logs no warning about an unknown `wow.*` key at startup.

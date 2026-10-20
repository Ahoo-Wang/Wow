---
title: Deployment Model
description: What a Wow system is made of at run time — services, nodes, their two ports, the stores and brokers they need — and the assumptions a deployment must meet.
---

# Deployment Model

This page describes what you run when you run Wow: the services and their nodes, how nodes find each other and share work, which infrastructure each service needs, and the assumptions the design makes about the network. Read it before you plan a deployment.

## Services and nodes

A **service** is one deployable application. It hosts one or more bounded contexts: their aggregates, their processors and their HTTP API. A **node** is one running instance of a service. A service runs as many nodes as it needs, and every node of a service runs the same code.

```mermaid
flowchart LR
    subgraph Cluster[One cluster]
        subgraph Ordering[ordering service]
            O1[node 1]
            O2[node 2]
        end
        subgraph Inventory[inventory service]
            I1[node 1]
            I2[node 2]
        end
        OPS[ops service]
    end
    CLIENT[Clients] -- public port --> O1 & O2
    O1 <-. internal port .-> O2
    I1 <-. internal port .-> I2
    O1 & O2 -. calls, signals .-> I1 & I2
    PG[(PostgreSQL or MongoDB)]
    KAFKA[(Kafka)]
    O1 & O2 & I1 & I2 --> PG
    O1 & O2 & I1 & I2 & OPS <--> KAFKA
```

The nodes of a service share the work through **partitions**. Each context's log has a fixed number of partitions, 64 by default, and each partition has one owner at a time. Ownership is a lease kept in the service's own store: there is no ZooKeeper, no Kafka consumer group and no registry. Every node computes the same assignment from the list of live members and takes or releases leases to match it. When a node joins or leaves, about one partition in N moves.

The owner of a partition executes the commands for its aggregates and runs every subscription over it. A command that arrives at another node is forwarded to the owner. When no owner can be reached, for example while a partition changes hands, the receiving node runs the command itself, and the aggregate's version keeps the result correct. [Writing: admission to append](../concepts/write-path#routing) explains the rules.

## Two ports

Each node listens on two ports:

| Port | Serves | Who reaches it |
| --- | --- | --- |
| Public (`server.port`) | The HTTP API: commands, queries, state reads, the Manifest, OpenAPI; Actuator's health endpoint | Clients, gateways, ingresses |
| Internal (`wow.internal.port`, 9090 by default) | The cluster channel between the service's own nodes; wait signals from any service; synchronous calls and reads from other services | Nodes of the system only |

The internal port is HTTP/2 without TLS (h2c). It must never be reachable from outside the cluster: no public load balancer, ingress or port mapping points at it. Every request to it is authenticated, with the service's cluster token or another service's identity ([Secure a deployment](./security)).

Each node advertises the address other nodes reach its internal port at:

- `wow.node.id` names the node among the service's members; a new one is generated at each start unless set.
- `wow.internal.host` is the interface the internal port binds. Bind it to the node's private address.
- `wow.node.address` is the `host:port` other nodes use, when it differs from the bound address, as behind NAT or in a container.

On Kubernetes, the eShop's ordering manifests derive all three from the pod:

<!-- sample: deploy/v10/eshop-ordering/deployment.yaml#node -->

```yaml
env:
  - name: POD_NAME
    valueFrom:
      fieldRef:
        fieldPath: metadata.name
  - name: POD_NAMESPACE
    valueFrom:
      fieldRef:
        fieldPath: metadata.namespace
  - name: POD_IP
    valueFrom:
      fieldRef:
        fieldPath: status.podIP
  - name: SERVER_PORT
    value: "8083"
  - name: WOW_INTERNAL_PORT
    value: "9093"
  # The node id is the pod's name: a replaced pod is a new member.
  - name: WOW_NODE_ID
    value: $(POD_NAME)
  # D173: the internal port binds the pod's private address only, and the node advertises its headless name.
  - name: WOW_INTERNAL_HOST
    value: $(POD_IP)
  - name: WOW_NODE_ADDRESS
    value: $(POD_NAME).eshop-ordering-cluster.$(POD_NAMESPACE).svc.cluster.local:9093
```

The address is the pod's name under a headless service that also publishes pods that are not ready, so a node that is starting or draining still receives its peers' forwards and signals. The [eShop ordering manifests](https://github.com/Ahoo-Wang/Wow/tree/v10/deploy/v10/eshop-ordering) are a complete template for one service.

## What a service needs

| Component | When | What for |
| --- | --- | --- |
| PostgreSQL or MongoDB | Always, in production | The log, snapshots, checkpoints, leases, the failure ledger, reserved keys and timers. MongoDB must be a replica set. |
| Kafka | When events or commands cross contexts, or an aggregate type's commands are routed through Kafka | Published streams, command topics, receipts. See [Kafka](./kafka). |
| ClickHouse | When the service writes the warehouse or serves datasets | The ClickHouse sink and dataset queries. |
| Elasticsearch | Optional | Copies of snapshots and event streams for full-text search. It never holds the log. |

A node keeps all of its own data in one backend, unless named stores split it per aggregate. The memory backend is for tests and trying things out: its data does not outlive the node. With `wow.backend: auto`, the default, a node that finds no durable backend falls back to memory only in a development profile; otherwise it does not start.

A system usually also runs the platform services, which are Wow services themselves: the **ops service**, which collects every service's parked aggregates and gives operators a console ([The ops service and console](./ops-console)); the **view store**, which keeps users' saved views; and the **analytics catalog**, which defines and publishes datasets.

## Assumptions

The design rests on a few assumptions about the environment. A deployment that breaks one of them breaks a guarantee.

- **One cluster.** All the services of a system run in the same cluster and can reach each other's internal ports directly. A wait that crosses into another service is signalled by that service's nodes straight to the node the command came from.
- **Synchronized clocks.** Leases expire by the nodes' clocks. The clock skew between nodes must be far below the lease time to live, 10 seconds by default.
- **One tenant space.** All the contexts of a system share one set of tenant ids. A tenant id crosses services as it is, in commands, published streams, receipts and timers, so every service must resolve a caller's tenant the same way.
- **Adjacent versions.** During a rolling upgrade, the nodes of a service may run two adjacent minor versions of the same major version, and no wider range. A major version upgrade is a blue-green switch.
- **A fixed partition count.** A context's partition count cannot change once its log holds entries. It must be a power of two that divides 4,096. Choose it before the first deployment; 64 suits most services.

## Starting and stopping

A node opens its public port only once it has started: its backends are up, its internal port is bound and it has joined the members. It stops in the reverse order:

1. Readiness reports that the node refuses traffic, and the public server stops taking requests. Waits still open finish, within `wow.wait.max-timeout` (60 seconds).
2. The node stops taking commands for its partitions, finishes those in flight within `wow.cluster.drain-timeout` (10 seconds), saves its subscriptions' checkpoints and releases its leases. Other nodes take the partitions over.
3. The internal port closes last.

The host sizes Spring Boot's shutdown phase to fit, unless `spring.lifecycle.timeout-per-shutdown-phase` is set. Give the platform's termination grace period at least as much; the eShop manifests use 90 seconds.

With Spring Boot Actuator on the classpath, the host contributes the node's health to the readiness and liveness groups:

<!-- sample: deploy/v10/eshop-ordering/deployment.yaml#probes -->

```yaml
startupProbe:
  httpGet:
    path: /actuator/health/readiness
    port: http
  periodSeconds: 5
  failureThreshold: 36
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: http
  periodSeconds: 5
  failureThreshold: 2
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: http
  periodSeconds: 10
  failureThreshold: 3
```

- **Readiness** fails until the node runs, while its log or lease store is down, while it owns none of its share of partitions past a hand-over grace period, or while a topic it reads is still joining.
- **Liveness** fails only when the node is stuck: its dispatcher or its commands make no progress while its stores answer. It never fails for a storage outage, and not while the node drains, so an outage does not restart every node at once.

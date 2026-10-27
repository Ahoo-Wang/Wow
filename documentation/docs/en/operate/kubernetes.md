---
title: Kubernetes
description: Run a service on Kubernetes with plain manifests, the image, the two services, the node's identity and address, probes, graceful shutdown, rolling updates and scaling.
outline: deep
---

# Kubernetes

A Wow service runs on Kubernetes as a Deployment: its pods are the service's nodes, they find each other through the service's database, and they reach each other through a headless Service. Nothing else is needed: no operator, no ZooKeeper, no Kafka consumer group. This page walks through the manifests of the eShop's ordering service, in `deploy/eshop-ordering`, which are a template for your own. [Deployment Model](./topology) explains the ideas behind them.

## The files

| File | Holds |
| --- | --- |
| `kustomization.yaml` | The set: `kubectl apply -k deploy/eshop-ordering` |
| `configmap.yaml` | The settings every node shares, as environment variables |
| `secret.yaml` | The database's connection: the MongoDB connection string |
| `deployment.yaml` | The nodes |
| `service.yaml` | The HTTP service, and the headless service of the internal port |
| `networkpolicy.yaml` | Who may reach each port: the gateway the HTTP port, the shop's own pods the internal port |
| `postgres/` | A component that puts the service on PostgreSQL instead |
| `Dockerfile` | The image, from the service's Gradle distribution |

## The image

The image runs the service's Gradle distribution (`./gradlew :eshop-ordering-server:installDist`) on a JDK 21 base, as a numeric non-root user, and exposes the HTTP port and the internal port.

## Shared settings

Every node of a service gets the same configuration from a ConfigMap, as environment variables that Spring Boot binds relaxed:

<!-- sample: deploy/eshop-ordering/configmap.yaml -->

```yaml
# The settings every node shares, as environment variables (Spring Boot's relaxed binding: wow.kafka.bootstrap-servers
# is WOW_KAFKA_BOOTSTRAPSERVERS). They override the application.yaml in the image, its profiles included.
apiVersion: v1
kind: ConfigMap
metadata:
  name: eshop-ordering-service-config
  labels:
    app.kubernetes.io/name: eshop-ordering-service
data:
  # The mongo profile keeps the service's data on MongoDB, the replica set at secret.yaml's URI (deploy/mongodb), in a
  # database of its own; the kafka profile connects it to the other contexts through Kafka; the cosec profile takes the
  # callers from the CoSec gateway in front of the service. The postgres component (postgres/)
  # puts the service on PostgreSQL instead.
  SPRING_PROFILES_ACTIVE: mongo,kafka,cosec
  WOW_KAFKA_BOOTSTRAPSERVERS: kafka:9092
  # The topics the service owns are created by the operator; the node only validates them. The kafka
  # profile's auto is for trying the service out.
  WOW_KAFKA_DDL: validate
  # Fixed once the context's log holds entries; the command topic wow.eshop-ordering.commands has as many partitions.
  WOW_LOG_PARTITIONS: "64"
  JAVA_OPTS: -XX:MaxRAMPercentage=75.0
  # Traces and metrics: the node's spans and Micrometer meters go through the OpenTelemetry Java agent,
  # which the OpenTelemetry Operator injects (the pod's instrumentation.opentelemetry.io/inject-java annotation, with
  # an Instrumentation resource in the namespace). Without the agent they are no-ops. The agent exports to the
  # collector below over OTLP, and bridges Micrometer's global registry.
  OTEL_SERVICE_NAME: eshop-ordering-service
  OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4318
  OTEL_EXPORTER_OTLP_PROTOCOL: http/protobuf
  OTEL_INSTRUMENTATION_MICROMETER_ENABLED: "true"
```

The secrets come from a Secret the same way: the database user and password. Replace the example values. The internal port needs no secret: the network is its boundary.

## The nodes

<!-- sample: deploy/eshop-ordering/deployment.yaml#rollout -->

```yaml
# The nodes share the partitions through leases in MongoDB: no ZooKeeper, no consumer group.
replicas: 2
selector:
  matchLabels:
    app.kubernetes.io/name: eshop-ordering-service
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1
    maxUnavailable: 0
```

Two replicas are two nodes, which split the log's partitions through leases in the database. A rolling update starts a new pod before it stops an old one (`maxUnavailable: 0`), so the partitions always have an owner.

Each node needs an identity and an address its peers can reach:

<!-- sample: deploy/eshop-ordering/deployment.yaml#node -->

```yaml
env:
  - name: POD_NAME
    valueFrom:
      fieldRef:
        fieldPath: metadata.name
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
  # The internal port binds the pod's private address only, and the node advertises that address,
  # $(POD_IP):9093: a Deployment's pods have no DNS name of their own.
  - name: WOW_INTERNAL_HOST
    value: $(POD_IP)
```

- **The node id** is the pod's name. A replaced pod joins as a new member; the old one's leases expire.
- **The internal port** binds the pod's own address, and the node advertises that address, `<pod IP>:9093`: the host's default, so the manifest sets no `wow.node.address`. A Deployment's pods have no DNS name of their own: a headless service publishes `<pod>.<service>` records only for pods with `spec.hostname`, which a Deployment cannot give each pod. Set `wow.node.address` only where the pod's own address is not the one its peers reach, such as a StatefulSet whose pods have stable names (`serviceName` and `hostname`) or a host with several interfaces.

## Two services

<!-- sample: deploy/eshop-ordering/service.yaml -->

```yaml
# The public HTTP port, for clients and ingresses.
apiVersion: v1
kind: Service
metadata:
  name: eshop-ordering-service
  labels:
    app.kubernetes.io/name: eshop-ordering-service
spec:
  selector:
    app.kubernetes.io/name: eshop-ordering-service
  ports:
    - name: http
      port: 80
      targetPort: http
      protocol: TCP
---
# The internal port's headless service, named after the service (spring.application.name) with
# -cluster: its SRV records, _internal._tcp.eshop-ordering-service-cluster, name every node's address, the address each
# node advertises (its pod IP), and the other services find the nodes through them. It publishes
# the nodes that are not ready, so a node that is starting or draining is still found. Never expose it
# outside the cluster: it takes no token, each node and service names itself on each request (Wow-Service),
# and only the shop's own pods reach it (networkpolicy.yaml).
apiVersion: v1
kind: Service
metadata:
  name: eshop-ordering-service-cluster
  labels:
    app.kubernetes.io/name: eshop-ordering-service
spec:
  clusterIP: None
  publishNotReadyAddresses: true
  selector:
    app.kubernetes.io/name: eshop-ordering-service
  ports:
    - name: internal
      port: 9093
      targetPort: internal
      protocol: TCP
```

Both are named after the service, its `spring.application.name`.

- `eshop-ordering-service` serves the public port to the gateway, through which clients reach it.
- `eshop-ordering-service-cluster` is headless: its SRV records, `_internal._tcp.eshop-ordering-service-cluster`, name every node's address, and other services find the nodes through them by the service's name alone (peer discovery). It publishes the nodes that are not ready, so a node that is starting or draining is still found.

Never expose the internal port outside the cluster. It takes no token: each request names its service (`Wow-Service`), and the network policies are the boundary. `networkpolicy.yaml` holds two: `eshop-ordering-service-public` admits only the gateway's pods, labelled `app.kubernetes.io/component: gateway` in any namespace, to the HTTP port, and `eshop-ordering-service-internal` admits only the shop's own pods, labelled `app.kubernetes.io/part-of: eshop`, to the internal port ([Secure a Deployment](./security#close-the-internal-port)). Label your gateway's pods so, or change the selector to match them. A network plugin that does not enforce NetworkPolicy ignores them; then keep the internal port off every network but the platform's by other means.

## Probes

The host gives the node's health to Spring Boot Actuator's readiness and liveness groups, on the HTTP port:

<!-- sample: deploy/eshop-ordering/deployment.yaml#probes -->

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

Readiness fails while the node cannot serve: before it runs, while its log or lease store is down, while it owns none of its share of partitions past the hand-over grace, or while a topic it reads is still joining. Liveness fails only when the node is stuck, never for a storage outage and not while it drains, so an outage does not restart every pod at once. The startup probe gives a node three minutes to start.

## Graceful shutdown

When Kubernetes stops a pod, the node first finishes the waits still open, then hands its partitions off after the commands in flight, saves its checkpoints, and closes its internal port last. The grace period must cover all of it:

<!-- sample: deploy/eshop-ordering/deployment.yaml#shutdown -->

```yaml
# The host's shutdown: the public server finishes its requests, the waits among them (wow.wait.max-timeout,
# 60s); the node then hands its partitions off after their commands in flight (wow.cluster.drain-timeout, 10s)
# and saves its checkpoints. 5s of preStop, 60s and 10s, with room to spare: raise it with those settings.
terminationGracePeriodSeconds: 90
```

A short `preStop` pause gives the endpoints time to drop the pod before Spring Boot stops taking requests:

<!-- sample: deploy/eshop-ordering/deployment.yaml#prestop -->

```yaml
lifecycle:
  # Time for the endpoints to drop the pod before Spring Boot stops taking requests.
  preStop:
    exec:
      command: ["sleep", "5"]
```

Raise the grace period when you raise `wow.wait.max-timeout` or `wow.cluster.drain-timeout`.

## Scale

Add replicas to spread the partitions over more nodes; remove them to gather them again. The leases move partitions between nodes as members join and leave, and a moved partition's subscriptions go on from their checkpoints. A node beyond the number of partitions, 64 by default, owns none, which is one reason the count is chosen up front.

## Before you apply

1. Build the image and push it, or load it into a local cluster.
2. Provide the database and, when the service uses it, Kafka. `deploy/mongodb` holds a MongoDB replica set to start from; for PostgreSQL, add the service's `postgres` component to a kustomization of your own (`components: [deploy/eshop-ordering/postgres]`), and set the server as [PostgreSQL](./postgresql#server-settings) says.
3. Create the Kafka topics the service owns, since the ConfigMap sets `WOW_KAFKA_DDL=validate`: a command topic needs exactly as many partitions as the log ([Kafka](./kafka#creating-topics)).
4. Replace the example secrets.

Then `kubectl apply -k deploy/eshop-ordering` and `kubectl rollout status deployment/eshop-ordering-service`. `kubectl apply -k deploy/eshop` applies the whole eShop.

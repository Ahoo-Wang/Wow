---
title: Kubernetes
description: Run a service on Kubernetes with plain manifests, the image, the two services, the node's identity and address, probes, graceful shutdown, rolling updates and scaling.
outline: deep
---

# Kubernetes

A Wow service runs on Kubernetes as a Deployment: its pods are the service's nodes, they find each other through the service's database, and they reach each other through a headless Service. Nothing else is needed: no operator, no ZooKeeper, no Kafka consumer group. This page walks through the manifests of the eShop's ordering service, in `deploy/v10/eshop-ordering`, which are a template for your own. [Deployment Model](./topology) explains the ideas behind them.

## The files

| File | Holds |
| --- | --- |
| `kustomization.yaml` | The set: `kubectl apply -k deploy/v10/eshop-ordering` |
| `configmap.yaml` | The settings every node shares, as environment variables |
| `secret.yaml` | The database credentials, the cluster token and the service tokens |
| `deployment.yaml` | The nodes |
| `service.yaml` | The HTTP service, and the headless service of the internal port |
| `networkpolicy.yaml` | Who may reach the internal port |
| `Dockerfile` | The image, from the service's Gradle distribution |

## The image

The image runs the service's Gradle distribution (`./gradlew :eshop-ordering-server:installDist`) on a JDK 21 base, as a numeric non-root user, and exposes the HTTP port and the internal port.

## Shared settings

Every node of a service gets the same configuration from a ConfigMap, as environment variables that Spring Boot binds relaxed:

<!-- sample: deploy/v10/eshop-ordering/configmap.yaml -->

```yaml
# The settings every node shares, as environment variables (Spring Boot's relaxed binding: wow.kafka.bootstrap-servers
# is WOW_KAFKA_BOOTSTRAPSERVERS). They override the application.yaml in the image, its postgres and kafka profiles
# included.
apiVersion: v1
kind: ConfigMap
metadata:
  name: eshop-ordering-config
  labels:
    app.kubernetes.io/name: eshop-ordering
data:
  # The postgres profile wires the DataSource; the kafka profile the transport and the command intake.
  SPRING_PROFILES_ACTIVE: postgres,kafka
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/eshop_ordering
  WOW_KAFKA_BOOTSTRAPSERVERS: kafka:9092
  # The topics the service owns are created by the operator; the node only validates them (decision D168). The kafka
  # profile's auto is for trying the service out.
  WOW_KAFKA_TOPICS_DDL: validate
  # Fixed once the context's log holds entries; the command topic wow.eshop-ordering.commands has as many partitions.
  WOW_LOG_PARTITIONS: "64"
  JAVA_OPTS: -XX:MaxRAMPercentage=75.0
```

The secrets come from Secrets the same way: the database password, the cluster token `WOW_INTERNAL_TOKEN`, and the tokens of the services the ordering trusts. Replace the example values.

## The nodes

<!-- sample: deploy/v10/eshop-ordering/deployment.yaml#rollout -->

```yaml
# The nodes share the partitions through leases in PostgreSQL: no ZooKeeper, no consumer group.
replicas: 2
selector:
  matchLabels:
    app.kubernetes.io/name: eshop-ordering
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1
    maxUnavailable: 0
```

Two replicas are two nodes, which split the log's partitions through leases in the database. A rolling update starts a new pod before it stops an old one (`maxUnavailable: 0`), so the partitions always have an owner.

Each node needs an identity and an address its peers can reach:

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

- **The node id** is the pod's name. A replaced pod joins as a new member; the old one's leases expire.
- **The internal port** binds the pod's own address, and the node advertises its name under the headless service, `<pod>.eshop-ordering-cluster.<namespace>.svc.cluster.local:9093`, which the pod's `subdomain` gives it.

## Two services

<!-- sample: deploy/v10/eshop-ordering/service.yaml -->

```yaml
# The public HTTP port, for clients and ingresses.
apiVersion: v1
kind: Service
metadata:
  name: eshop-ordering
  labels:
    app.kubernetes.io/name: eshop-ordering
spec:
  selector:
    app.kubernetes.io/name: eshop-ordering
  ports:
    - name: http
      port: 80
      targetPort: http
      protocol: TCP
---
# The cluster channel and the wait signals (decisions D89, D173): a headless service that gives each node the DNS name
# it advertises, <pod>.eshop-ordering-cluster.<namespace>.svc.cluster.local. It publishes the nodes that are not
# ready, so a node that is starting or draining still gets its peers' forwarded commands and signals. Never expose it
# outside the cluster: its forwards and signals take the cluster token (WOW_INTERNAL_TOKEN, decision D226), its calls a
# service token, but it is the service's inside.
apiVersion: v1
kind: Service
metadata:
  name: eshop-ordering-cluster
  labels:
    app.kubernetes.io/name: eshop-ordering
spec:
  clusterIP: None
  publishNotReadyAddresses: true
  selector:
    app.kubernetes.io/name: eshop-ordering
  ports:
    - name: internal
      port: 9093
      targetPort: internal
      protocol: TCP
```

- `eshop-ordering` serves the public port to clients and ingresses.
- `eshop-ordering-cluster` is headless: it gives each node its DNS name. It publishes the nodes that are not ready, so a node that is starting or draining still receives the commands its peers forward and the signals that complete waits.

Never expose the internal port outside the cluster. The network policy admits only the shop's own pods to it, and the port itself authenticates every request with the cluster token or a service token ([Secure a Deployment](./security#close-the-internal-port)). A network plugin that does not enforce NetworkPolicy ignores it.

## Probes

The host gives the node's health to Spring Boot Actuator's readiness and liveness groups, on the HTTP port:

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

Readiness fails while the node cannot serve: before it runs, while its log or lease store is down, while it owns none of its share of partitions past the hand-over grace, or while a topic it reads is still joining. Liveness fails only when the node is stuck, never for a storage outage and not while it drains, so an outage does not restart every pod at once. The startup probe gives a node three minutes to start.

## Graceful shutdown

When Kubernetes stops a pod, the node first finishes the waits still open, then hands its partitions off after the commands in flight, saves its checkpoints, and closes its internal port last. The grace period must cover all of it:

<!-- sample: deploy/v10/eshop-ordering/deployment.yaml#shutdown -->

```yaml
# The host's shutdown: the public server finishes its requests, the waits among them (wow.wait.max-timeout,
# 60s); the node then hands its partitions off after their commands in flight (wow.cluster.drain-timeout, 10s)
# and saves its checkpoints. 5s of preStop, 60s and 10s, with room to spare: raise it with those settings.
terminationGracePeriodSeconds: 90
```

A short `preStop` pause gives the endpoints time to drop the pod before Spring Boot stops taking requests:

<!-- sample: deploy/v10/eshop-ordering/deployment.yaml#prestop -->

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
2. Provide the database and, when the service uses it, Kafka.
3. Create the Kafka topics the service owns, since the ConfigMap sets `WOW_KAFKA_TOPICS_DDL=validate`: a command topic needs exactly as many partitions as the log ([Kafka](./kafka#creating-topics)).
4. Replace the example secrets.

Then `kubectl apply -k deploy/v10/eshop-ordering` and `kubectl rollout status deployment/eshop-ordering`.

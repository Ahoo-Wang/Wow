---
title: Kubernetes
description: Run a service on Kubernetes with plain manifests, the image, the two services, the node's identity and address, probes, graceful shutdown, rolling updates and scaling.
outline: deep
---

# Kubernetes

A Wow service runs on Kubernetes as a Deployment: its pods are the service's nodes, they find each other through the service's database, and they reach each other through a headless Service. Nothing else is needed: no operator, no ZooKeeper, no Kafka consumer group. Wow's Helm chart, `deploy/helm/wow-service`, renders those objects; this page walks through them with the eShop's ordering service, whose values in `deploy/eshop-ordering` are a template for your own. [Deployment Model](./topology) explains the ideas behind them.

## The files

| File | Holds |
| --- | --- |
| `deploy/helm/wow-service` | The chart: the Deployment, its ConfigMap, the HTTP Service and the internal port's headless Service, the network policies, the disruption budget |
| `build/wow/helm/values.yaml` | The values the service's build generates from its contract (`generateWowHelmValues`): its name and its peers |
| `values.yaml` | The service's own values: the image, the ports, the profiles, the settings every node shares, the secret |
| `values-postgres.yaml` | Passed after `values.yaml`: the service on PostgreSQL instead |
| `secret.yaml`, `postgres-secret.yaml` | The database's connection, example values |
| `Dockerfile` | The image, from the service's Gradle distribution |

## The image

The image runs the service's Gradle distribution (`./gradlew :eshop-ordering-server:installDist`) on a JDK 25 base, pinned by digest, as a numeric non-root user, and exposes the HTTP port and the internal port.

## The JDK

Wow is built for JDK 21 and runs on it, but run it on JDK 25 in production. The backends make their blocking driver calls (JDBC, the MongoDB and Kafka clients) on virtual threads. Before JDK 24, a virtual thread that blocks inside `synchronized` code, as drivers and Java agents often do, pins the carrier thread it runs on. Enough of them at once leave no carrier for the others: every store and topic call of the node stops returning, its leases lapse, and the other nodes take its partitions. The node's liveness then fails after `wow.health.liveness.stall-timeout`, and Kubernetes restarts it ([Health](./observability#health)). JDK 24 and later let those threads unmount. A node on an earlier JDK warns of this once when it starts.

## Shared settings

Every node of a service gets the same configuration from a ConfigMap, as environment variables that Spring Boot binds relaxed:

<!-- sample: deploy/eshop-ordering/values.yaml -->

```yaml
# The eshop-ordering service's own values for the wow-service chart (deploy/helm/wow-service), on MongoDB and Kafka:
# what the build does not know. Pass them after the values its build generates, so they override them:
#
#   ./gradlew :eshop-ordering-server:generateWowHelmValues
#   helm install eshop-ordering deploy/helm/wow-service \
#     -f examples/eshop/ordering/ordering-server/build/wow/helm/values.yaml -f deploy/eshop-ordering/values.yaml
#
# values-postgres.yaml after these puts the service on PostgreSQL instead.
image:
  # The image built from this repository (README.md).
  repository: wow-eshop-ordering-server
  tag: local
# The ports of the image's own configuration (application.yaml), which the chart sets on the pod.
ports:
  http: 8083
  internal: 9093
# The nodes share the partitions through leases in the service's database: no ZooKeeper, no consumer group.
replicas: 2
# The internal port admits the pods of the platform, labelled app.kubernetes.io/part-of: eshop.
partOf: eshop
config:
  # The mongo profile keeps the service's data on MongoDB, the replica set at secret.yaml's URI (deploy/mongodb), in a
  # database of its own; the kafka profile connects it to the other contexts through Kafka; the cosec profile takes the
  # callers from the CoSec gateway in front of the service.
  SPRING_PROFILES_ACTIVE: mongo,kafka,cosec
  WOW_KAFKA_BOOTSTRAPSERVERS: kafka:9092
  # The topics the service owns are created by the operator; the node only validates them. The kafka profile's auto
  # is for trying the service out.
  WOW_KAFKA_DDL: validate
  # Fixed once the context's log holds entries; the command topic wow.eshop-ordering.commands has as many partitions.
  WOW_LOG_PARTITIONS: "64"
  # Traces and metrics: the node's spans and Micrometer meters go through the OpenTelemetry Java agent, which the
  # OpenTelemetry Operator injects (podAnnotations, with an Instrumentation resource in the namespace). Without the
  # agent they are no-ops. It exports to the collector below over OTLP, and bridges Micrometer's global registry.
  OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4318
  OTEL_EXPORTER_OTLP_PROTOCOL: http/protobuf
  OTEL_INSTRUMENTATION_MICROMETER_ENABLED: "true"
# The database's connection: secret.yaml, an example to replace.
envFrom:
  - secretRef:
      name: eshop-ordering-service-mongo
podAnnotations:
  instrumentation.opentelemetry.io/inject-java: "true"
resources:
  requests:
    cpu: 500m
    memory: 768Mi
  limits:
    memory: 1Gi
```

The database's connection comes from a Secret the same way: the MongoDB connection string, or, with the postgres component, the database user and password. Replace the example values. The internal port needs no secret: the network is its boundary.

## The nodes

<!-- sample: deploy/helm/wow-service/templates/deployment.yaml#rollout -->

```yaml
# A new node joins before an old one leaves: the partitions move to it through the leases, and none is left without
# an owner while the rollout runs.
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: {{ .Values.rollout.maxSurge }}
    maxUnavailable: 0
```

Two replicas are two nodes, which split the log's partitions through leases in the database. A rolling update starts a new pod before it stops an old one (`maxUnavailable: 0`), so the partitions always have an owner.

Each node needs an identity and an address its peers can reach:

<!-- sample: deploy/helm/wow-service/templates/deployment.yaml#node -->

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
  # The ports the Services and the probes name: the chart's, whatever the image's configuration says.
  - name: SERVER_PORT
    value: {{ .Values.ports.http | quote }}
  - name: WOW_INTERNAL_PORT
    value: {{ .Values.ports.internal | quote }}
  # The node id is the pod's name: a replaced pod is a new member.
  - name: WOW_NODE_ID
    value: $(POD_NAME)
  # The internal port binds the pod's private address only, and the node advertises that address: a
  # Deployment's pods have no DNS name of their own.
  - name: WOW_INTERNAL_HOST
    value: $(POD_IP)
  # The namespace a peer's SRV name is in, unless wow.peer.dns.namespaces places the peer elsewhere.
  - name: WOW_PEER_DNS_NAMESPACE
    valueFrom:
      fieldRef:
        fieldPath: metadata.namespace
  {{- with .Values.env }}
  {{- toYaml . | nindent 12 }}
  {{- end }}
```

- **The node id** is the pod's name. A replaced pod joins as a new member; the old one's leases expire.
- **The internal port** binds the pod's own address, and the node advertises that address, `<pod IP>:9093`: the host's default, so the manifest sets no `wow.node.address`. A Deployment's pods have no DNS name of their own: a headless service publishes `<pod>.<service>` records only for pods with `spec.hostname`, which a Deployment cannot give each pod. Set `wow.node.address` only where the pod's own address is not the one its peers reach, such as a StatefulSet whose pods have stable names (`serviceName` and `hostname`) or a host with several interfaces.

## Two services

<!-- sample: deploy/helm/wow-service/templates/service.yaml -->

```yaml
{{- $name := include "wow-service.name" . -}}
# The public HTTP port, for clients, the gateway and ingresses.
apiVersion: v1
kind: Service
metadata:
  name: {{ $name }}
  labels:
    {{- include "wow-service.labels" . | nindent 4 }}
  {{- with .Values.publicService.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
spec:
  type: {{ .Values.publicService.type }}
  selector:
    {{- include "wow-service.selectorLabels" . | nindent 4 }}
  ports:
    - name: http
      port: {{ .Values.publicService.port }}
      targetPort: http
      protocol: TCP
---
# The internal port's headless Service, named after the service with -cluster: its SRV records,
# _internal._tcp.<service>-cluster.<namespace>.svc, name every node's address, the address each node advertises (its pod
# IP), and the other services find the nodes through them. It publishes the nodes that are not ready, so a node that is
# starting or draining is still found. Never expose it outside the cluster: it takes no token, each node and service
# names itself on each request (Wow-Service), and the internal NetworkPolicy admits the platform's own pods alone.
apiVersion: v1
kind: Service
metadata:
  name: {{ $name }}-cluster
  labels:
    {{- include "wow-service.labels" . | nindent 4 }}
spec:
  clusterIP: None
  publishNotReadyAddresses: true
  selector:
    {{- include "wow-service.selectorLabels" . | nindent 4 }}
  ports:
    - name: internal
      port: {{ .Values.ports.internal }}
      targetPort: internal
      protocol: TCP
```

Both are named after the service, its `spring.application.name`.

- `eshop-ordering-service` serves the public port to the gateway, through which clients reach it.
- `eshop-ordering-service-cluster` is headless: its SRV records, `_internal._tcp.eshop-ordering-service-cluster`, name every node's address, and other services find the nodes through them by the service's name alone (peer discovery). It publishes the nodes that are not ready, so a node that is starting or draining is still found.

Never expose the internal port outside the cluster. It takes no token: each request names its service (`Wow-Service`), and the network policies are the boundary. `networkpolicy.yaml` holds two: `eshop-ordering-service-public` admits only the gateway's pods, labelled `app.kubernetes.io/component: gateway` in any namespace, to the HTTP port, and `eshop-ordering-service-internal` admits only the shop's own pods, labelled `app.kubernetes.io/part-of: eshop`, to the internal port ([Secure a Deployment](./security#close-the-internal-port)). Label your gateway's pods so, or change the selector to match them. A network plugin that does not enforce NetworkPolicy ignores them; then keep the internal port off every network but the platform's by other means.

## Probes

The host gives the node's health to Spring Boot Actuator's readiness and liveness groups, on the HTTP port:

<!-- sample: deploy/helm/wow-service/templates/deployment.yaml#probes -->

```yaml
startupProbe:
  httpGet:
    path: /actuator/health/readiness
    port: http
  periodSeconds: {{ .Values.probes.startup.periodSeconds }}
  failureThreshold: {{ .Values.probes.startup.failureThreshold }}
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: http
  periodSeconds: {{ .Values.probes.readiness.periodSeconds }}
  failureThreshold: {{ .Values.probes.readiness.failureThreshold }}
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: http
  periodSeconds: {{ .Values.probes.liveness.periodSeconds }}
  failureThreshold: {{ .Values.probes.liveness.failureThreshold }}
```

Readiness fails while the node cannot serve: before it runs, while its log, a store its writes need (a type's primary snapshot store) or its lease store is down, while it owns none of its share of partitions past the hand-over grace, or while a topic it reads is still joining. Liveness fails only when the node is stuck, never for a storage outage and not while it drains, so an outage does not restart every pod at once. The startup probe gives a node three minutes to start.

## Graceful shutdown

When Kubernetes stops a pod, the node first finishes the waits still open, then hands its partitions off after the commands in flight, saves its checkpoints, and closes its internal port last. The grace period must cover all of it:

<!-- sample: deploy/helm/wow-service/templates/deployment.yaml#shutdown -->

```yaml
# The preStop pause, the waits the public server finishes (shutdown.waitMaxTimeoutSeconds, wow.wait.max-timeout),
# the hand-off of the partitions after their commands in flight (shutdown.drainTimeoutSeconds,
# wow.cluster.drain-timeout) and a margin: the chart sets both keys from the same values.
terminationGracePeriodSeconds: {{ include "wow-service.terminationGracePeriodSeconds" . }}
```

A short `preStop` pause gives the endpoints time to drop the pod before Spring Boot stops taking requests:

<!-- sample: deploy/helm/wow-service/templates/deployment.yaml#prestop -->

```yaml
lifecycle:
  # Time for the endpoints to drop the pod before Spring Boot stops taking requests.
  preStop:
    exec:
      command: ["sleep", "{{ .Values.shutdown.preStopSeconds }}"]
```

Raise the grace period when you raise `wow.wait.max-timeout` or `wow.cluster.drain-timeout`.

## Scale

Add replicas to spread the partitions over more nodes; remove them to gather them again. The leases move partitions between nodes as members join and leave, and a moved partition's subscriptions go on from their checkpoints. A node beyond the number of partitions, 64 by default, owns none, which is one reason the count is chosen up front.

## Before you apply

1. Build the image and push it, or load it into a local cluster.
2. Provide the database and, when the service uses it, Kafka. `deploy/mongodb` holds a MongoDB replica set to start from; for PostgreSQL, pass `values-postgres.yaml` after `values.yaml`, and set the server as [PostgreSQL](./postgresql#server-settings) says.
3. Create the Kafka topics the service owns, since `values.yaml` sets `WOW_KAFKA_DDL=validate`: a command topic needs exactly as many partitions as the log ([Kafka](./kafka#creating-topics)).
4. Replace the example secrets.

Then `helm upgrade --install eshop-ordering deploy/helm/wow-service -f examples/eshop/ordering/ordering-server/build/wow/helm/values.yaml -f deploy/eshop-ordering/values.yaml --wait`. [`deploy/eshop`](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/eshop) installs the whole eShop.

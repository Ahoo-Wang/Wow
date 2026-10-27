# eShop ordering on Kubernetes

These are plain manifests for one Wow service: the eShop ordering service (`example/eshop/ordering`), on MongoDB and Kafka, with two nodes, or on PostgreSQL through its `postgres` component. Use them as a template for your own service. [Deployment model](https://wow.ahoo.me/operate/topology) explains the settings they use.

| File | What it holds |
| --- | --- |
| `kustomization.yaml` | The set: `kubectl apply -k deploy/eshop-ordering` |
| `configmap.yaml` | The settings every node shares, as environment variables: profiles, the Kafka brokers, the log partitions |
| `secret.yaml` | The MongoDB connection string, an example value; add your credentials |
| `deployment.yaml` | The nodes. Each has its HTTP port (`8083`) and its internal port (`9093`). The node id is the pod name. The internal port binds the pod IP, which the node advertises. The file also sets the probes and the shutdown grace period |
| `service.yaml` | `eshop-ordering-service`, the HTTP service; `eshop-ordering-service-cluster`, the headless service of the internal port, whose SRV records the other services look up |
| `networkpolicy.yaml` | Only the gateway's pods (`app.kubernetes.io/component: gateway`) reach the HTTP port, and only pods labelled `app.kubernetes.io/part-of: eshop` the internal port; the internal port takes no token |
| `postgres/` | A component that puts the service on PostgreSQL: the `postgres` profile, its URL and its credentials instead of the MongoDB connection string |
| `Dockerfile` | The image, built from the service's Gradle distribution |

## Before you apply

1. Build the image, from the repository root:

   ```bash
   ./gradlew :eshop-ordering-server:installDist
   docker build -f deploy/eshop-ordering/Dockerfile -t wow-eshop-ordering-server:local \
     example/eshop/ordering/ordering-server
   ```

   For a local cluster, load the image into it (`kind load docker-image wow-eshop-ordering-server:local`). Otherwise, push it to your registry and change `image` in `deployment.yaml`.

2. Provide MongoDB and Kafka. `secret.yaml` expects the replica set of [`deploy/mongodb`](../mongodb/README.md) (the service's database is `eshop_ordering`), `configmap.yaml` Kafka at `kafka:9092`. The host creates its collections at startup.

   On PostgreSQL instead, apply the service with its `postgres` component, from a kustomization of your own:

   ```yaml
   resources: [deploy/eshop-ordering]
   components: [deploy/eshop-ordering/postgres]
   ```

   It expects PostgreSQL at `postgres:5432` (database `eshop_ordering`), with the server's TCP keepalives set as [Operate › PostgreSQL](https://wow.ahoo.me/operate/postgresql#server-settings) says. The host creates its tables at startup.

3. Create the Kafka topics the ordering service owns. `configmap.yaml` sets `WOW_KAFKA_DDL=validate`: a node does not start while one of them is missing, or while its command topic does not have exactly `WOW_LOG_PARTITIONS` partitions, and its log names each such topic:

   ```bash
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.commands --partitions 64
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.receipts --partitions 64
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.order.state --partitions 64
   ```

   The topics it reads belong to the services that write them: the inventory service owns `wow.eshop-inventory.commands` (64 partitions, its log's) and `wow.eshop-inventory.stock.state`. On a cluster for trying it out, set `WOW_KAFKA_DDL=auto` instead, and each service creates the topics it owns.

## Apply and check

```bash
kubectl apply -k deploy/eshop-ordering
kubectl rollout status deployment/eshop-ordering-service
kubectl port-forward service/eshop-ordering-service 8083:80
curl -s localhost:8083/wow/manifest
```

## Choices

- **Internal port.** It is reachable only from the shop's own pods: it takes no token, and each node and service names itself on each request (`Wow-Service`), which catches a misconfiguration, such as a call the ordering does not list for the basket (`wow.callers`). Each node advertises its pod IP, the host's default: a Deployment's pods have no DNS name of their own. The headless service is named after the service, `spring.application.name` with `-cluster`, so its SRV records `_internal._tcp.eshop-ordering-service-cluster` name every node, those not ready included: the basket finds the ordering's nodes there by itself, with no URL to set. Keep the names when you copy the manifests for a service of your own. Other eShop services that wait across contexts need the label `app.kubernetes.io/part-of: eshop` to pass the NetworkPolicy. A cluster whose network plugin does not enforce NetworkPolicy ignores it.
- **Probes.** The server has Spring Boot Actuator's health endpoint, and the host adds the node's health to its probe groups: the startup and readiness probes read `/actuator/health/readiness`, the liveness probe `/actuator/health/liveness`, both on the HTTP port, so the internal port stays reachable from the eShop's pods alone. The readiness fails until the node runs, while its log or lease store is down, while it owns none of its partitions past the hand-over grace (`wow.health.ownership.handover-grace`), or while a topic it reads is still joining; the host also reports `REFUSING_TRAFFIC` when it stops. The liveness fails only when the node is stuck (its dispatcher or its commands make no progress while the stores answer), never for a storage outage and not while the node drains, so an outage does not restart every pod. `/actuator/health/wow` has every component; Actuator shows only the status unless `management.endpoint.health.show-details` says otherwise.
- **Shutdown.** `terminationGracePeriodSeconds: 90` covers the 5 s `preStop` pause, the waits still open (`wow.wait.max-timeout`, 60 s) and the partition hand-off (`wow.cluster.drain-timeout`, 10 s). Raise it when you raise those settings.
- **Node id.** It is the pod name. A replaced pod joins as a new member, and the leases of the old one expire.

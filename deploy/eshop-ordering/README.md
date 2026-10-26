# eShop ordering on Kubernetes

These are plain manifests for one Wow service: the eShop ordering service (`example/eshop/ordering`), on PostgreSQL and Kafka, with two nodes. Use them as a template for your own service. [Deployment model](https://wow.ahoo.me/operate/topology) explains the settings they use.

| File | What it holds |
| --- | --- |
| `kustomization.yaml` | The set: `kubectl apply -k deploy/eshop-ordering` |
| `configmap.yaml` | The settings every node shares, as environment variables: profiles, the PostgreSQL URL, the Kafka brokers, the log partitions |
| `secret.yaml` | The PostgreSQL user and password. These are example values; replace them |
| `deployment.yaml` | The nodes. Each has its HTTP port (`8083`) and its internal port (`9093`). The node id is the pod name. The internal port binds the pod IP and is advertised under the headless name. The file also sets the probes and the shutdown grace period |
| `service.yaml` | `eshop-ordering`, the HTTP service; `eshop-ordering-cluster`, the headless service of the internal port |
| `networkpolicy.yaml` | Only the gateway's pods (`app.kubernetes.io/component: gateway`) reach the HTTP port, and only pods labelled `app.kubernetes.io/part-of: eshop` the internal port (decisions D173, D300); the internal port takes no token |
| `Dockerfile` | The image, built from the service's Gradle distribution |

## Before you apply

1. Build the image, from the repository root:

   ```bash
   ./gradlew :eshop-ordering-server:installDist
   docker build -f deploy/eshop-ordering/Dockerfile -t wow-eshop-ordering-server:local \
     example/eshop/ordering/ordering-server
   ```

   For a local cluster, load the image into it (`kind load docker-image wow-eshop-ordering-server:local`). Otherwise, push it to your registry and change `image` in `deployment.yaml`.

2. Provide PostgreSQL and Kafka. `configmap.yaml` expects them at `postgres:5432` (database `eshop_ordering`) and `kafka:9092`. The host creates its tables at startup.

3. Create the Kafka topics the ordering service owns. `configmap.yaml` sets `WOW_KAFKA_TOPICS_DDL=validate` (decision D168): a node does not start while one of them is missing, or while its command topic does not have exactly `WOW_LOG_PARTITIONS` partitions, and its log names each such topic:

   ```bash
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.commands --partitions 64
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.receipts --partitions 64
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.order.state --partitions 64
   ```

   The topics it reads belong to the services that write them: the inventory service owns `wow.eshop-inventory.commands` (64 partitions, its log's) and `wow.eshop-inventory.stock.state`. On a cluster for trying it out, set `WOW_KAFKA_TOPICS_DDL=auto` instead, and each service creates the topics it owns.

## Apply and check

```bash
kubectl apply -k deploy/eshop-ordering
kubectl rollout status deployment/eshop-ordering
kubectl port-forward service/eshop-ordering 8083:80
curl -s localhost:8083/wow/manifest
```

## Choices

- **Internal port.** It is reachable only from the shop's own pods (D300): it takes no token, and each node and service names itself on each request (`Wow-Service`), which catches a misconfiguration, such as a call the ordering does not list for the basket (`wow.http.services`). The headless service publishes nodes that are not ready, so a node that is starting or draining still gets its peers' forwards and wait signals. Other eShop services that wait across contexts need the label `app.kubernetes.io/part-of: eshop` to pass the NetworkPolicy. A cluster whose network plugin does not enforce NetworkPolicy ignores it.
- **Probes.** The server has Spring Boot Actuator's health endpoint, and the host adds the node's health to its probe groups: the startup and readiness probes read `/actuator/health/readiness`, the liveness probe `/actuator/health/liveness`, both on the HTTP port, so the internal port stays reachable from the eShop's pods alone. The readiness fails until the node runs, while its log or lease store is down, while it owns none of its partitions past the hand-over grace (`wow.health.ownership.handover-grace`), or while a topic it reads is still joining; the host also reports `REFUSING_TRAFFIC` when it stops. The liveness fails only when the node is stuck (its dispatcher or its commands make no progress while the stores answer), never for a storage outage and not while the node drains, so an outage does not restart every pod. `/actuator/health/wow` has every component; Actuator shows only the status unless `management.endpoint.health.show-details` says otherwise.
- **Shutdown.** `terminationGracePeriodSeconds: 90` covers the 5 s `preStop` pause, the waits still open (`wow.wait.max-timeout`, 60 s) and the partition hand-off (`wow.cluster.drain-timeout`, 10 s). Raise it when you raise those settings.
- **Node id.** It is the pod name. A replaced pod joins as a new member, and the leases of the old one expire.

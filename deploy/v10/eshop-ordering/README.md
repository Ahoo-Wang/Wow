# eShop ordering on Kubernetes (Wow V10)

These are plain manifests for one V10 service: the eShop ordering service (`example/eshop/ordering`), on PostgreSQL and Kafka, with two nodes. Use them as a template for your own service. [Deploying a V10 Service](https://wow.ahoo.me/guide/v10/deployment) explains the settings they use.

| File | What it holds |
| --- | --- |
| `kustomization.yaml` | The set: `kubectl apply -k deploy/v10/eshop-ordering` |
| `configmap.yaml` | The settings every node shares, as environment variables: profiles, the PostgreSQL URL, the Kafka brokers, the log partitions |
| `secret.yaml` | The PostgreSQL user and password. These are example values; replace them |
| `deployment.yaml` | The nodes. Each has its HTTP port (`8083`) and its internal port (`9093`). The node id is the pod name. The internal port binds the pod IP and is advertised under the headless name. The file also sets the probes and the shutdown grace period |
| `service.yaml` | `eshop-ordering`, the HTTP service; `eshop-ordering-cluster`, the headless service of the internal port |
| `networkpolicy.yaml` | Only pods labelled `app.kubernetes.io/part-of: eshop` may reach the internal port (decision D173) |
| `Dockerfile` | The image, built from the service's Gradle distribution |

## Before you apply

1. Build the image, from the repository root:

   ```bash
   ./gradlew :eshop-ordering-server:installDist
   docker build -f deploy/v10/eshop-ordering/Dockerfile -t wow-eshop-ordering-server:local \
     example/eshop/ordering/ordering-server
   ```

   For a local cluster, load the image into it (`kind load docker-image wow-eshop-ordering-server:local`). Otherwise, push it to your registry and change `image` in `deployment.yaml`.

2. Provide PostgreSQL and Kafka. `configmap.yaml` expects them at `postgres:5432` (database `eshop_ordering`) and `kafka:9092`. The host creates its tables at startup.

3. Create the Kafka topics. The node does not create them:

   ```bash
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.commands --partitions 64
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-inventory.commands --partitions 64
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.receipts --partitions 1
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.order.state --partitions 2
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-inventory.stock.state --partitions 2
   ```

   The command topics must have exactly `WOW_LOG_PARTITIONS` partitions. Otherwise a node with the command intake does not start.

## Apply and check

```bash
kubectl apply -k deploy/v10/eshop-ordering
kubectl rollout status deployment/eshop-ordering
kubectl port-forward service/eshop-ordering 8083:80
curl -s localhost:8083/wow/manifest
```

## Choices

- **Internal port.** It is not authenticated (D173), so it is reachable only inside the cluster. The headless service publishes nodes that are not ready, so a node that is starting or draining still gets its peers' forwards and wait signals. Other eShop services that wait across contexts need the label `app.kubernetes.io/part-of: eshop` to pass the NetworkPolicy. A cluster whose network plugin does not enforce NetworkPolicy ignores it.
- **Probes.** The host has no health endpoint of its own. The public server starts only after the node has started and stops before the node, so the HTTP port is the readiness probe. The internal port serves until the node has stopped, so it is the liveness probe, and a draining node is not restarted. With Spring Boot Actuator on the classpath, use `/actuator/health/readiness` and `/actuator/health/liveness` instead; the host reports `REFUSING_TRAFFIC` when it stops.
- **Shutdown.** `terminationGracePeriodSeconds: 90` covers the 5 s `preStop` pause, the waits still open (`wow.wait.max-timeout`, 60 s) and the partition hand-off (`wow.cluster.drain-timeout`, 10 s). Raise it when you raise those settings.
- **Node id.** It is the pod name. A replaced pod joins as a new member, and the leases of the old one expire.

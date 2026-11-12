# eShop ordering on Kubernetes

The eShop ordering service (`examples/eshop/ordering`) deployed with Wow's chart, [`deploy/helm/wow-service`](../helm/wow-service/README.md), on MongoDB and Kafka with two nodes, or on PostgreSQL. Use it as a template for your own service. [Operate › Kubernetes](https://wow.ahoo.me/operate/kubernetes) explains the chart's choices.

| File | What it holds |
| --- | --- |
| `values.yaml` | The service's own values, after the generated ones: the image, the ports, the replicas, the platform (`partOf: eshop`), the profiles, the Kafka brokers, the log partitions, OpenTelemetry, the secret and the resources |
| `values-postgres.yaml` | Passed after `values.yaml`: the `postgres` profile, the database's URL and its secret instead of MongoDB's |
| `secret.yaml` | The MongoDB connection string, an example value; add your credentials |
| `postgres-secret.yaml` | The PostgreSQL credentials, example values |
| `Dockerfile` | The image, built from the service's Gradle distribution |

The build writes the values it knows from the service's contract, `examples/eshop/ordering/ordering-server/build/wow/helm/values.yaml` (`generateWowHelmValues`): the service's name, `eshop-ordering-service`, which names every object, and the peers it calls. Never copy them into `values.yaml`: they follow the code.

## Before you install

1. Build the image and the values, from the repository root:

   ```bash
   ./gradlew :eshop-ordering-server:installDist :eshop-ordering-server:generateWowHelmValues
   docker build -f deploy/eshop-ordering/Dockerfile -t wow-eshop-ordering-server:local \
     examples/eshop/ordering/ordering-server
   ```

   For a local cluster, load the image into it (`kind load docker-image wow-eshop-ordering-server:local`). Otherwise, push it to your registry and set `image` in a values file of your own.

2. Provide MongoDB and Kafka. `secret.yaml` expects the replica set of [`deploy/mongodb`](../mongodb/README.md) (the service's database is `eshop_ordering`), `values.yaml` Kafka at `kafka:9092`. The host creates its collections at startup. Apply the secret: `kubectl apply -f deploy/eshop-ordering/secret.yaml`.

   On PostgreSQL instead, apply `postgres-secret.yaml` and pass `values-postgres.yaml` after `values.yaml`. It expects PostgreSQL at `postgres:5432` (database `eshop_ordering`), with the server's TCP keepalives set as [Operate › PostgreSQL](https://wow.ahoo.me/operate/postgresql#server-settings) says. The host creates its tables at startup.

3. Create the Kafka topics the ordering service owns. `values.yaml` sets `WOW_KAFKA_DDL=validate`: a node does not start while one of them is missing, or while its command topic does not have exactly `WOW_LOG_PARTITIONS` partitions, and its log names each such topic:

   ```bash
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.commands --partitions 64 --replication-factor 3 --config min.insync.replicas=2
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.receipts --partitions 64
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.order.state --partitions 64
   ```

   The topics it reads belong to the services that write them: the inventory service owns `wow.eshop-inventory.commands` (64 partitions, its log's) and `wow.eshop-inventory.stock.state`. On a cluster for trying it out, set `WOW_KAFKA_DDL=auto` instead, and each service creates the topics it owns.

## Install and check

```bash
helm upgrade --install eshop-ordering deploy/helm/wow-service \
  -f examples/eshop/ordering/ordering-server/build/wow/helm/values.yaml \
  -f deploy/eshop-ordering/values.yaml --wait
kubectl port-forward service/eshop-ordering-service 8083:80
curl -s localhost:8083/wow/manifest
```

## Choices

- **The order numbers** (`SO-<yyMMdd>-<sequence>`, the orders' ids): every node reserves ranges of one sequence from the service's own database, at the same URI (CosId's mongo store; on PostgreSQL its jdbc store, whose tables it creates at the first start), set by the profile in the image (`eshop-ordering-ids.yaml`). The generator is a segment and needs no machine slot, so the replica count needs no setting, nor a StatefulSet.
- **Internal port.** It is reachable only from the shop's own pods (`partOf: eshop`) and the peers it calls: it takes no token, and each node and service names itself on each request (`Wow-Service`), which catches a misconfiguration, such as a call the ordering does not list for the basket (`wow.callers`). The headless Service `eshop-ordering-service-cluster` publishes every node, those not ready included: the basket finds the ordering's nodes through its SRV records by itself, with no URL to set. A cluster whose network plugin does not enforce NetworkPolicy ignores the policies.
- **The rest** is the chart's: the probes on Actuator's readiness and liveness groups, a grace period that covers the `preStop` pause, the waits and the partition hand-off (`shutdown.*`), the pod name as the node id, one node at a time in a rollout and a disruption.

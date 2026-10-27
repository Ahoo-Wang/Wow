# eShop inventory on Kubernetes

Plain manifests for the eShop inventory service (`example/eshop/inventory`): the stock, its reservations and their release. On MongoDB and Kafka, with two nodes, or on PostgreSQL through its `postgres` component. They follow the [eShop ordering manifests](../eshop-ordering/README.md), file for file; the choices there (internal port, probes, shutdown, node id) hold here too.

The HTTP port is `8084`, the internal port `9094`. The objects are named after the service, `eshop-inventory-service`: the HTTP service `eshop-inventory-service`, the headless service `eshop-inventory-service-cluster`, whose SRV records the other services look up (decision D305).

## Before you apply

1. Build the image, from the repository root:

   ```bash
   ./gradlew :eshop-inventory-server:installDist
   docker build -f deploy/eshop-inventory/Dockerfile -t wow-eshop-inventory-server:local example/eshop/inventory/inventory-server
   ```

2. Provide MongoDB and Kafka: the replica set of [`deploy/mongodb`](../mongodb/README.md) (the service's database is `eshop_inventory`), Kafka at `kafka:9092`. On PostgreSQL instead, add the `postgres` component (`components: [deploy/eshop-inventory/postgres]`), which expects `postgres:5432` (database `eshop_inventory`).

3. Create the Kafka topics the service owns (`WOW_KAFKA_DDL=validate`, decision D168): a node does not start while one is missing, or while its command topic does not have exactly `WOW_LOG_PARTITIONS` partitions:

   ```bash
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-inventory.commands --partitions 64
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-inventory.stock.state --partitions 64
   ```

## Apply and check

```bash
kubectl apply -k deploy/eshop-inventory
kubectl rollout status deployment/eshop-inventory-service
```

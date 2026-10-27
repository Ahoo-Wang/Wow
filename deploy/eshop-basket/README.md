# eShop basket on Kubernetes

Plain manifests for the eShop basket service (`example/eshop/basket`): the buyers' baskets and their checkout, which submits the order to the ordering service's internal port, found by DNS. On MongoDB and Kafka, with two nodes, or on PostgreSQL through its `postgres` component. They follow the [eShop ordering manifests](../eshop-ordering/README.md), file for file; the choices there (internal port, probes, shutdown, node id) hold here too.

The HTTP port is `8082`, the internal port `9092`. The objects are named after the service, `eshop-basket-service`: the HTTP service `eshop-basket-service`, the headless service `eshop-basket-service-cluster`, whose SRV records the other services look up.

## Before you apply

1. Build the image, from the repository root:

   ```bash
   ./gradlew :eshop-basket-server:installDist
   docker build -f deploy/eshop-basket/Dockerfile -t wow-eshop-basket-server:local example/eshop/basket/basket-server
   ```

2. Provide MongoDB and Kafka: the replica set of [`deploy/mongodb`](../mongodb/README.md) (the service's database is `eshop_basket`), Kafka at `kafka:9092`. On PostgreSQL instead, add the `postgres` component (`components: [deploy/eshop-basket/postgres]`), which expects `postgres:5432` (database `eshop_basket`).

3. Create the Kafka topics the service owns (`WOW_KAFKA_DDL=validate`): a node does not start while one is missing, or while its command topic does not have exactly `WOW_LOG_PARTITIONS` partitions. The command topic carries the operators' actions on its failure ledger (ops):

   ```bash
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-basket.commands --partitions 64
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-basket.receipts --partitions 64
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-basket.basket.state --partitions 64
   ```

## Apply and check

```bash
kubectl apply -k deploy/eshop-basket
kubectl rollout status deployment/eshop-basket-service
```

# eShop basket on Kubernetes

The eShop basket service (`examples/eshop/basket`): the buyers' baskets and their checkout, which submits the order to the ordering service's internal port, found by DNS, deployed with Wow's chart ([`deploy/helm/wow-service`](../helm/wow-service/README.md)) on MongoDB and Kafka with two nodes, or on PostgreSQL (`values-postgres.yaml`). Its files follow [eShop ordering](../eshop-ordering/README.md)'s, file for file, and the choices there hold here too.

The HTTP port is `8082`, the internal port `9092`. The build's generated values name every object after the service, `eshop-basket-service`: the HTTP service `eshop-basket-service`, the headless service `eshop-basket-service-cluster`, whose SRV records the other services look up.

## Before you install

1. Build the image, from the repository root:

   ```bash
   ./gradlew :eshop-basket-server:installDist :eshop-basket-server:generateWowHelmValues
   docker build -f deploy/eshop-basket/Dockerfile -t wow-eshop-basket-server:local examples/eshop/basket/basket-server
   ```

2. Provide MongoDB and Kafka: the replica set of [`deploy/mongodb`](../mongodb/README.md) (the service's database is `eshop_basket`), Kafka at `kafka:9092`. Apply `secret.yaml`, or on PostgreSQL instead `postgres-secret.yaml` with `values-postgres.yaml` passed after `values.yaml`, which expects `postgres:5432` (database `eshop_basket`).

3. Create the Kafka topics the service owns (`WOW_KAFKA_DDL=validate`): a node does not start while one is missing, or while its command topic does not have exactly `WOW_LOG_PARTITIONS` partitions. The command topic carries the operators' actions on its failure ledger (ops):

   ```bash
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-basket.commands --partitions 64 --replication-factor 3 --config min.insync.replicas=2
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-basket.receipts --partitions 64
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-basket.basket.state --partitions 64
   ```

## Install and check

```bash
helm upgrade --install eshop-basket deploy/helm/wow-service \
  -f examples/eshop/basket/basket-server/build/wow/helm/values.yaml -f deploy/eshop-basket/values.yaml --wait
```

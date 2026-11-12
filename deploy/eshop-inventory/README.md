# eShop inventory on Kubernetes

The eShop inventory service (`examples/eshop/inventory`): the stock, its reservations and their release, deployed with Wow's chart ([`deploy/helm/wow-service`](../helm/wow-service/README.md)) on MongoDB and Kafka with two nodes, or on PostgreSQL (`values-postgres.yaml`). Its files follow [eShop ordering](../eshop-ordering/README.md)'s, file for file, and the choices there hold here too.

The HTTP port is `8084`, the internal port `9094`. The build's generated values name every object after the service, `eshop-inventory-service`: the HTTP service `eshop-inventory-service`, the headless service `eshop-inventory-service-cluster`, whose SRV records the other services look up.

## Before you install

1. Build the image, from the repository root:

   ```bash
   ./gradlew :eshop-inventory-server:installDist :eshop-inventory-server:generateWowHelmValues
   docker build -f deploy/eshop-inventory/Dockerfile -t wow-eshop-inventory-server:local examples/eshop/inventory/inventory-server
   ```

2. Provide MongoDB and Kafka: the replica set of [`deploy/mongodb`](../mongodb/README.md) (the service's database is `eshop_inventory`), Kafka at `kafka:9092`. Apply `secret.yaml`, or on PostgreSQL instead `postgres-secret.yaml` with `values-postgres.yaml` passed after `values.yaml`, which expects `postgres:5432` (database `eshop_inventory`).

3. Create the Kafka topics the service owns (`WOW_KAFKA_DDL=validate`): a node does not start while one is missing, or while its command topic does not have exactly `WOW_LOG_PARTITIONS` partitions:

   ```bash
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-inventory.commands --partitions 64 --replication-factor 3 --config min.insync.replicas=2
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-inventory.stock.state --partitions 64
   ```

## Install and check

```bash
helm upgrade --install eshop-inventory deploy/helm/wow-service \
  -f examples/eshop/inventory/inventory-server/build/wow/helm/values.yaml -f deploy/eshop-inventory/values.yaml --wait
```

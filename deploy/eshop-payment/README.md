# eShop payment on Kubernetes

The eShop payment service (`examples/eshop/payment`): the payments, through a simulated payment gateway, deployed with Wow's chart ([`deploy/helm/wow-service`](../helm/wow-service/README.md)) on MongoDB and Kafka with two nodes, or on PostgreSQL (`values-postgres.yaml`). Its files follow [eShop ordering](../eshop-ordering/README.md)'s, file for file, and the choices there hold here too. `WOW_KAFKA_COMMANDINTAKE_ENABLED` executes the commands other services send to `wow.eshop-payment.commands`: an operator's retry of a parked payment, from ops, among them.

The HTTP port is `8085`, the internal port `9095`. The build's generated values name every object after the service, `eshop-payment-service`: the HTTP service `eshop-payment-service`, the headless service `eshop-payment-service-cluster`, whose SRV records the other services look up.

## Before you install

1. Build the image, from the repository root:

   ```bash
   ./gradlew :eshop-payment-server:installDist :eshop-payment-server:generateWowHelmValues
   docker build -f deploy/eshop-payment/Dockerfile -t wow-eshop-payment-server:local examples/eshop/payment/payment-server
   ```

2. Provide MongoDB and Kafka: the replica set of [`deploy/mongodb`](../mongodb/README.md) (the service's database is `eshop_payment`), Kafka at `kafka:9092`. Apply `secret.yaml`, or on PostgreSQL instead `postgres-secret.yaml` with `values-postgres.yaml` passed after `values.yaml`, which expects `postgres:5432` (database `eshop_payment`).

3. Create the Kafka topics the service owns (`WOW_KAFKA_DDL=validate`): a node does not start while one is missing, or while its command topic does not have exactly `WOW_LOG_PARTITIONS` partitions:

   ```bash
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-payment.commands --partitions 64 --replication-factor 3 --config min.insync.replicas=2
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-payment.receipts --partitions 64
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-payment.payment.state --partitions 64
   ```

## Install and check

```bash
helm upgrade --install eshop-payment deploy/helm/wow-service \
  -f examples/eshop/payment/payment-server/build/wow/helm/values.yaml -f deploy/eshop-payment/values.yaml --wait
```

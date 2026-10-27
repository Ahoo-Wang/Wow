# eShop payment on Kubernetes

Plain manifests for the eShop payment service (`examples/eshop/payment`): the payments, through a simulated payment gateway. On MongoDB and Kafka, with two nodes, or on PostgreSQL through its `postgres` component. They follow the [eShop ordering manifests](../eshop-ordering/README.md), file for file; the choices there (internal port, probes, shutdown, node id) hold here too. `WOW_KAFKA_COMMANDINTAKE_ENABLED` executes the commands other services send to `wow.eshop-payment.commands`: an operator's resume of a parked payment, from ops, among them.

The HTTP port is `8085`, the internal port `9095`. The objects are named after the service, `eshop-payment-service`: the HTTP service `eshop-payment-service`, the headless service `eshop-payment-service-cluster`, whose SRV records the other services look up.

## Before you apply

1. Build the image, from the repository root:

   ```bash
   ./gradlew :eshop-payment-server:installDist
   docker build -f deploy/eshop-payment/Dockerfile -t wow-eshop-payment-server:local examples/eshop/payment/payment-server
   ```

2. Provide MongoDB and Kafka: the replica set of [`deploy/mongodb`](../mongodb/README.md) (the service's database is `eshop_payment`), Kafka at `kafka:9092`. On PostgreSQL instead, add the `postgres` component (`components: [deploy/eshop-payment/postgres]`), which expects `postgres:5432` (database `eshop_payment`).

3. Create the Kafka topics the service owns (`WOW_KAFKA_DDL=validate`): a node does not start while one is missing, or while its command topic does not have exactly `WOW_LOG_PARTITIONS` partitions:

   ```bash
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-payment.commands --partitions 64
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-payment.receipts --partitions 64
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-payment.payment.state --partitions 64
   ```

## Apply and check

```bash
kubectl apply -k deploy/eshop-payment
kubectl rollout status deployment/eshop-payment-service
```

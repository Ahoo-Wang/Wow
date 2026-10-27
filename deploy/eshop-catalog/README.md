# eShop catalog on Kubernetes

Plain manifests for the eShop catalog service (`example/eshop/catalog`): the products, their brands and categories, and their search. On MongoDB, with two nodes, or on PostgreSQL through its `postgres` component. They follow the [eShop ordering manifests](../eshop-ordering/README.md), file for file; the choices there (internal port, probes, shutdown, node id) hold here too. It has no Kafka profile: no other context reads its streams through Kafka.

The HTTP port is `8081`, the internal port `9091`. The objects are named after the service, `eshop-catalog-service`: the HTTP service `eshop-catalog-service`, the headless service `eshop-catalog-service-cluster`, whose SRV records the other services look up.

## Before you apply

1. Build the image, from the repository root:

   ```bash
   ./gradlew :eshop-catalog-server:installDist
   docker build -f deploy/eshop-catalog/Dockerfile -t wow-eshop-catalog-server:local example/eshop/catalog/catalog-server
   ```

2. Provide MongoDB: the replica set of [`deploy/mongodb`](../mongodb/README.md) (the service's database is `eshop_catalog`). On PostgreSQL instead, add the `postgres` component (`components: [deploy/eshop-catalog/postgres]`), which expects `postgres:5432` (database `eshop_catalog`).

## Apply and check

```bash
kubectl apply -k deploy/eshop-catalog
kubectl rollout status deployment/eshop-catalog-service
```

# eShop catalog on Kubernetes

The eShop catalog service (`examples/eshop/catalog`): the products, their brands and categories, and their search (its `search` profile, on Elasticsearch, which these files do not set), deployed with Wow's chart ([`deploy/helm/wow-service`](../helm/wow-service/README.md)) on MongoDB with two nodes, or on PostgreSQL (`values-postgres.yaml`). Its files follow [eShop ordering](../eshop-ordering/README.md)'s, file for file, and the choices there hold here too. It has no Kafka profile: no other context reads its streams through Kafka.

The HTTP port is `8081`, the internal port `9091`. The build's generated values name every object after the service, `eshop-catalog-service`: the HTTP service `eshop-catalog-service`, the headless service `eshop-catalog-service-cluster`, whose SRV records the other services look up.

## Before you install

1. Build the image, from the repository root:

   ```bash
   ./gradlew :eshop-catalog-server:installDist :eshop-catalog-server:generateWowHelmValues
   docker build -f deploy/eshop-catalog/Dockerfile -t wow-eshop-catalog-server:local examples/eshop/catalog/catalog-server
   ```

2. Provide MongoDB: the replica set of [`deploy/mongodb`](../mongodb/README.md) (the service's database is `eshop_catalog`). Apply `secret.yaml`, or on PostgreSQL instead `postgres-secret.yaml` with `values-postgres.yaml` passed after `values.yaml`, which expects `postgres:5432` (database `eshop_catalog`).

## Install and check

```bash
helm upgrade --install eshop-catalog deploy/helm/wow-service \
  -f examples/eshop/catalog/catalog-server/build/wow/helm/values.yaml -f deploy/eshop-catalog/values.yaml --wait
```

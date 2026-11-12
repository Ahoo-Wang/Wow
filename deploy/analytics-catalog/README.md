# The analytics catalog on Kubernetes

The analytics catalog (`platform/analytics/catalog/server`): the datasets of the ClickHouse warehouse the services' Sinks write to (the ordering's, with its `clickhouse` profile, which `deploy/eshop-ordering` leaves off), their descriptions and their queries, on MongoDB (or PostgreSQL), behind CoSec, with two nodes. It is deployed with Wow's chart ([`deploy/helm/wow-service`](../helm/wow-service/README.md)), as [eShop ordering](../eshop-ordering/README.md) is, and the choices there hold here too.

| File | What it holds |
| --- | --- |
| `values.yaml` | The service's own values, after the generated ones: the released image, the ports (`8087`, `9097`), the replicas, the platform (`partOf: wow-platform`), the profiles, the log partitions, OpenTelemetry, the secret and the resources |
| `values-postgres.yaml` | Passed after `values.yaml`: the `postgres` profile, the database's URL and its secret instead of MongoDB's |
| `secret.yaml` | The MongoDB connection string and the ClickHouse password, example values; replace them |
| `postgres-secret.yaml` | The PostgreSQL credentials and the ClickHouse password, example values |

The build writes the values it knows from the service's contract (`generateWowHelmValues`): the service's name, `analytics-catalog-service`, which names every object (the HTTP service `analytics-catalog-service`, the headless service `analytics-catalog-service-cluster`), and the peers it calls.

## Before you install

1. Build the image, from the repository root:

   ```bash
   ./gradlew :analytics-catalog-server:installDist :analytics-catalog-server:generateWowHelmValues
   docker build -t wow-analytics-catalog-server:local platform/analytics/catalog/server
   ```

   For a local cluster, load the image into it (`kind load docker-image wow-analytics-catalog-server:local`). Otherwise use the released image, which `values.yaml` names, or push yours and set `image` in a values file of your own.

2. Provide MongoDB and ClickHouse: the replica set of [`deploy/mongodb`](../mongodb/README.md) (the service's database is `wow_analytics_catalog`) and `http://clickhouse:8123`. Apply `secret.yaml`, or on PostgreSQL instead `postgres-secret.yaml` with `values-postgres.yaml` passed after `values.yaml`, which expects `postgres:5432` (database `wow_analytics_catalog`). The host creates its collections or tables at startup; the catalog creates none in ClickHouse.

3. Put the CoSec gateway in front, its pods labelled `app.kubernetes.io/component: gateway`: it verifies each request's access token and forwards the identity it verified in `CoSec-Identity`, which the service reads (CoSec's gateway mode, `wow.cosec.mode: gateway`), holding no secret. The service believes any such header, so only the gateway may reach its HTTP port (the chart's `analytics-catalog-service-public` NetworkPolicy).

## Install and check

```bash
helm upgrade --install analytics-catalog deploy/helm/wow-service \
  -f platform/analytics/catalog/server/build/wow/helm/values.yaml -f deploy/analytics-catalog/values.yaml --wait
```

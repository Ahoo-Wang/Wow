# The analytics catalog on Kubernetes

Plain manifests for the analytics catalog (`platform/analytics/catalog/server`): the datasets of the ClickHouse warehouse the services' Sinks write to, their descriptions and their queries, on MongoDB (or PostgreSQL, through its `postgres` component), behind CoSec, with two nodes. They follow the [eShop ordering manifests](../eshop-ordering/README.md); the choices there (internal port, probes, shutdown, node id) hold here too.

| File | What it holds |
| --- | --- |
| `kustomization.yaml` | The set: `kubectl apply -k deploy/analytics-catalog` |
| `configmap.yaml` | The settings every node shares, as environment variables: the profiles (`mongo,cosec`), the ClickHouse endpoint and user, the log partitions, OpenTelemetry |
| `secret.yaml` | The MongoDB connection string and the ClickHouse password, example values; replace them |
| `postgres/` | A component that puts the service on PostgreSQL: the `postgres` profile, its URL and its credentials |
| `deployment.yaml` | The nodes: the HTTP port (`8087`) and the internal port (`9097`); probes, shutdown, and the OpenTelemetry Operator's annotation |
| `service.yaml` | `analytics-catalog-service`, the HTTP service; `analytics-catalog-service-cluster`, the headless service of the internal port |
| `networkpolicy.yaml` | Only the CoSec gateway's pods reach the HTTP port, and only the platform's own pods the internal port |

## Before you apply

1. Build the image, from the repository root:

   ```bash
   ./gradlew :analytics-catalog-server:installDist
   docker build -t wow-analytics-catalog-server:local platform/analytics/catalog/server
   ```

   For a local cluster, load the image into it (`kind load docker-image wow-analytics-catalog-server:local`). Otherwise push it to your registry and change `image` in `deployment.yaml`.

2. Provide MongoDB and ClickHouse: the replica set of [`deploy/mongodb`](../mongodb/README.md) (the service's database is `wow_analytics_catalog`) and `http://clickhouse:8123`. On PostgreSQL instead, add the `postgres` component (`components: [deploy/analytics-catalog/postgres]`), which expects `postgres:5432` (database `wow_analytics_catalog`). The host creates its collections or tables at startup; the catalog creates none in ClickHouse.

3. Put the CoSec gateway in front, its pods labelled `app.kubernetes.io/component: gateway`: it verifies each request's access token and forwards the identity it verified in `CoSec-Identity`, which the service reads (CoSec's gateway mode, `wow.cosec.mode: gateway`), holding no secret. The service believes any such header, so only the gateway may reach its HTTP port (`networkpolicy.yaml`).

## Apply and check

```bash
kubectl apply -k deploy/analytics-catalog
kubectl rollout status deployment/analytics-catalog-service
```

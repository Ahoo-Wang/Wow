# The view store on Kubernetes

Plain manifests for the view store service (`platform/view-store/view-store-server`): saved views, preferences and dataset wording of the view engine's front ends, on MongoDB (or PostgreSQL, through its `postgres` component), behind a CoSec gateway, with two nodes. They follow the [eShop ordering manifests](../eshop-ordering/README.md); the choices there (internal port, probes, shutdown, node id) hold here too.

| File | What it holds |
| --- | --- |
| `kustomization.yaml` | The set: `kubectl apply -k deploy/view-store` |
| `configmap.yaml` | The settings every node shares, as environment variables: the profiles (`mongo,cosec`), the trust in the gateway's application, the log partitions, OpenTelemetry |
| `secret.yaml` | The MongoDB connection string, an example value; add your credentials |
| `postgres/` | A component that puts the service on PostgreSQL: the `postgres` profile, its URL and its credentials |
| `deployment.yaml` | The nodes: the HTTP port (`8080`) and the internal port (`9090`); probes, shutdown, and the OpenTelemetry Operator's annotation |
| `service.yaml` | `view-store-service`, the HTTP service; `view-store-service-cluster`, the headless service of the internal port |
| `networkpolicy.yaml` | Only the CoSec gateway's pods reach the HTTP port, and only the platform's own pods the internal port |

## Before you apply

1. Build the image, from the repository root:

   ```bash
   ./gradlew :view-store-server:installDist
   docker build -t wow-view-store-server:local platform/view-store/view-store-server
   ```

   For a local cluster, load the image into it (`kind load docker-image wow-view-store-server:local`). Otherwise use a released 10.x image (`ahoowang/wow-view-store-server:<version>`; its `latest` is still the 9.x view store) or push yours, and change `image` in `deployment.yaml`.

2. Provide MongoDB: the replica set of [`deploy/mongodb`](../mongodb/README.md) (the service's database is `view_store`). The host creates its collections at startup. On PostgreSQL instead, add the `postgres` component (`components: [deploy/view-store/postgres]`), which expects `postgres:5432` (database `view_store`). The view store needs no Kafka: its nodes share the partitions through leases in their store and forward commands over the internal port.

3. Put the CoSec gateway in front: it verifies each request's access token, and the service, in CoSec's inject mode, parses the token it forwards and holds no secret.

4. Label your CoSec gateway's pods `app.kubernetes.io/component: gateway`, or change the selector in `networkpolicy.yaml` to match them, and route `/view-store/**` to the `view-store` service. A gateway whose policies allow paths one by one allows, beside the aggregates' command and query routes, the view store's own reads, which the Manifest lists as its service routes: `GET /view-store/system-views` (the system views, `?definitionId=` for one definition's) and `GET /view-store/views/{id}` (one view by id, personal, shared or system).

## Apply and check

```bash
kubectl apply -k deploy/view-store
kubectl rollout status deployment/view-store-service
```

Through the gateway, a front end's request carries the user's access token (`Authorization: Bearer`) and its application (`CoSec-App-Id`); the gateway authorizes it by the application's roles and forwards it. A request without a token is answered 401.

## Choices

- **The application.** Every view, preference and wording is kept in the application the request was authorized in (the request fact `app_id`), and read only there. CoSec does not authenticate `CoSec-App-Id`: the service records it only when CoSec authorized the request by a permission of that application's roles. Behind the gateway, the gateway did that, and the service trusts the application it forwards (`WOW_COSEC_TRUSTINJECTEDAPP=true`, the property `wow.cosec.trust-injected-app`, which applies to CoSec's inject mode alone). That trust holds only while nothing else reaches the HTTP port: hence `networkpolicy.yaml`, and a cluster whose network plugin enforces it. Turn the switch off, and every query is refused for want of an application. A view store reachable without the gateway authorizes its requests itself instead: CoSec's authorization (`cosec.authorization.enabled=true`, `cosec.inject.enabled=false`) with CoSec's policy and app role permission repositories, which the image does not bring.
- **Users and roles.** The user is the token's principal, the owner of its own views. Every user reads the shared views (`Wow-Scope: owner="(shared)"`) and the stored system views (`Wow-Scope: tenant="(platform)", owner="(system)"`); `view-store.shared-writer` writes the shared views, preferences and the tenant's dataset wording, `view-store.system-writer` the system views.
- **Traces and metrics.** As for [the ops service](../ops/README.md#choices): the OpenTelemetry Operator injects the Java agent (`instrumentation.opentelemetry.io/inject-java`), `configmap.yaml` names the service and the collector, and without the agent the module records into no-ops.

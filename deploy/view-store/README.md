# The view store on Kubernetes

The view store service (`platform/view-store/view-store-server`): saved views, preferences and dataset wording of the view engine's front ends, on MongoDB (or PostgreSQL), behind a CoSec gateway, with two nodes. It is deployed with Wow's chart ([`deploy/helm/wow-service`](../helm/wow-service/README.md)), as [eShop ordering](../eshop-ordering/README.md) is, and the choices there hold here too.

| File | What it holds |
| --- | --- |
| `values.yaml` | The service's own values, after the generated ones: the released image, the ports (`8080`, `9090`), the replicas, the platform (`partOf: wow-platform`), the profiles, the log partitions, OpenTelemetry, the secret and the resources |
| `values-postgres.yaml` | Passed after `values.yaml`: the `postgres` profile, the database's URL and its secret instead of MongoDB's |
| `secret.yaml` | The MongoDB connection string, an example value; add your credentials |
| `postgres-secret.yaml` | The PostgreSQL credentials, example values |

The build writes the values it knows from the service's contract (`generateWowHelmValues`): the service's name, `view-store-service`, which names every object (the HTTP service `view-store-service`, the headless service `view-store-service-cluster`), and the peers it calls.

## Before you install

1. Build the image, from the repository root:

   ```bash
   ./gradlew :view-store-server:installDist :view-store-server:generateWowHelmValues
   docker build -t wow-view-store-server:local platform/view-store/view-store-server
   ```

   For a local cluster, load the image into it (`kind load docker-image wow-view-store-server:local`). Otherwise use a released 10.x image (`ahoowang/wow-view-store-server:<version>`; its `latest` is still the 9.x view store) or push yours, and set `image` in a values file of your own.

2. Provide MongoDB: the replica set of [`deploy/mongodb`](../mongodb/README.md) (the service's database is `view_store`). The host creates its collections at startup. Apply `secret.yaml`, or on PostgreSQL instead `postgres-secret.yaml` with `values-postgres.yaml` passed after `values.yaml`, which expects `postgres:5432` (database `view_store`). The view store needs no Kafka: its nodes share the partitions through leases in their store and forward commands over the internal port.

3. Put the CoSec gateway in front: it verifies each request's access token and forwards the identity it verified in `CoSec-Identity`, which the service reads (CoSec's gateway mode, `wow.cosec.mode: gateway`), holding no secret.

4. Label your CoSec gateway's pods `app.kubernetes.io/component: gateway`, or set `networkPolicy.public.from` to match them, and route `/view-store/**` to the `view-store-service` service. A gateway whose policies allow paths one by one allows, beside the aggregates' command and query routes, the view store's own reads, which the Manifest lists as its service routes: `GET /view-store/system-views` (the system views, `?definitionId=` for one definition's) and `GET /view-store/views/{id}` (one view by id, personal, shared or system).

## Install and check

```bash
helm upgrade --install view-store deploy/helm/wow-service \
  -f platform/view-store/view-store-server/build/wow/helm/values.yaml -f deploy/view-store/values.yaml --wait
```

Through the gateway, a front end's request carries the user's access token (`Authorization: Bearer`) and its application (`CoSec-App-Id`); the gateway decides it in that application and forwards the user, the application and the user's roles in `CoSec-Identity`. A request without a token is answered 401.

## Choices

- **The application.** Every view, preference and wording is kept in the application of the request's CoSec scope (the request fact `app_id`), and read only there: the application the gateway decided the request in and forwarded in `CoSec-Identity`, never a `CoSec-App-Id` the service receives itself. An identity without an application writes and reads nothing. The service believes the header as it comes, so this holds only while nothing but the gateway reaches the HTTP port: hence the chart's `view-store-service-public` NetworkPolicy, and a cluster whose network plugin enforces it.
- **Users and roles.** The user is the principal the gateway forwards, the owner of its own views. Every user reads the shared views (`Wow-Scope: owner="(shared)"`) and the stored system views (`Wow-Scope: tenant="(platform)", owner="(system)"`); `view-store.shared-writer` writes the shared views, preferences and the tenant's dataset wording, `view-store.system-writer` the system views.
- **Traces and metrics.** As for [the ops service](../ops/README.md#choices): the OpenTelemetry Operator injects the Java agent (`instrumentation.opentelemetry.io/inject-java`), `values.yaml` names the collector, and without the agent the module records into no-ops.

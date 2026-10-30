# The ops service on Kubernetes

Plain manifests for the ops service (`platform/ops/ops-server`): every service's parked entries in one place and the operator's console, on MongoDB and Kafka (or PostgreSQL, through its `postgres` component), behind CoSec, with two nodes. They follow the [eShop ordering manifests](../eshop-ordering/README.md); the choices there (internal port, probes, shutdown, node id) hold here too.

| File | What it holds |
| --- | --- |
| `kustomization.yaml` | The set: `kubectl apply -k deploy/ops` |
| `configmap.yaml` | The settings every node shares, as environment variables: the profiles (`mongo,kafka,cosec`), the Kafka brokers, the log partitions, OpenTelemetry |
| `secret.yaml` | The MongoDB connection string, an example value; add your credentials |
| `postgres/` | A component that puts the service on PostgreSQL: the `postgres` profile, its URL and its credentials |
| `deployment.yaml` | The nodes: the HTTP port (`8088`: the console and the ops routes) and the internal port (`9098`); probes, shutdown, and the OpenTelemetry Operator's annotation |
| `service.yaml` | `ops-service`, the HTTP service; `ops-service-cluster`, the headless service of the internal port |
| `networkpolicy.yaml` | Only the CoSec gateway's pods reach the HTTP port, and only the platform's own pods the internal port |

## Before you apply

1. Build the image, from the repository root, with the console in it:

   ```bash
   pnpm --filter wow-ops-console... build
   ./gradlew :ops-server:installDist
   docker build -t wow-ops-server:local platform/ops/ops-server
   ```

   For a local cluster, load the image into it (`kind load docker-image wow-ops-server:local`). Otherwise use a released image (`ahoowang/wow-ops-server:<version>`) or push yours, and change `image` in `deployment.yaml`.

2. Provide MongoDB and Kafka: the replica set of [`deploy/mongodb`](../mongodb/README.md) (the service's database is `wow_ops`) and `kafka:9092`. The host creates its collections at startup. On PostgreSQL instead, add the `postgres` component (`components: [deploy/ops/postgres]`), which expects `postgres:5432` (database `wow_ops`).

3. Create the Kafka topics the ops service owns. `WOW_KAFKA_DDL=validate`: a node does not start while one is missing, or while its command topic does not have exactly `WOW_LOG_PARTITIONS` partitions, and its log names each such topic:

   ```bash
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.ops.commands --partitions 64
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.ops.receipts --partitions 64
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.ops.parked.state --partitions 64
   ```

   Every service whose ledger the console shows publishes its entries to `wow.ops.commands` (by itself once this service is deployed: `wow.ops.publish.enabled` is `auto` by default); an operator's action goes to that service's own command topic, `wow.<context>.commands`, which it owns. On a cluster for trying it out, set `WOW_KAFKA_DDL=auto` instead.

4. Put the CoSec gateway in front and route `/` to the `ops-service` service: it verifies each request's access token and forwards the identity it verified in `CoSec-Identity`, which the service reads, holding no secret. Label the gateway's pods `app.kubernetes.io/component: gateway`, or change the selector in `networkpolicy.yaml` to match them.

## Apply and sign in

```bash
kubectl apply -k deploy/ops
kubectl rollout status deployment/ops-service
```

Open the console through the CoSec gateway's route, and sign in with an access token your CoSec issued (the console's sign-in page; a CoSec single sign-on that stored its token in this browser signs the console in too). Reached around the gateway (a `kubectl port-forward` to the service, say), every request is anonymous and answered 401. The service reads the operator from the identity the gateway forwards:

- **Roles.** `ops.operator` acts on the entries (retry, skip, mark recoverable, apply a retry spec); `ops.viewer` only reads them. A principal with neither reads nothing: both are tenant-level roles (`wow.cosec.tenant-level.roles` in the `cosec` profile), whose scope has no owner.
- **Tenant.** The services' ledgers publish to Wow's default tenant, `(0)`, which a principal of CoSec's default tenant is in.
- **Application.** The console calls as `CoSec-App-Id: ops-console`, the app the gateway decides the request in. The ops service does not use it to decide anything.

A request without a token, or with one the gateway refuses or that has expired, is answered 401; the console then signs out.

## Choices

- **CoSec.** The `cosec` profile runs CoSec's gateway mode (`wow.cosec.mode: gateway`) behind the CoSec gateway: the gateway verifies each token and decides the request, and the service reads the principal, its tenant and its roles from the `CoSec-Identity` header the gateway forwards, holding no secret, documents or Redis of CoSec's. It believes any such header, so only the gateway may reach the HTTP port (`networkpolicy.yaml`, in a cluster whose network plugin enforces it). Whether a caller is an operator is the service's to say, by its roles.
- **Traces and metrics.** The image carries Wow's observability module (`wow-observability`), whose spans and meters go through the OpenTelemetry Java agent. The pod asks the [OpenTelemetry Operator](https://opentelemetry.io/docs/kubernetes/operator/automatic/) to inject it (`instrumentation.opentelemetry.io/inject-java`), which needs an `Instrumentation` resource in the namespace. Where nothing injects the agent, the module records into no-ops. `configmap.yaml` names the service and the collector (`OTEL_*`); `wow.observability.enabled=false` turns the module off.
- **Internal port.** Reached by the service's own nodes only. The services whose entries it shows reach it through Kafka.

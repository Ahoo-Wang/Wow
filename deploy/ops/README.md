# The ops service on Kubernetes

Plain manifests for the ops service (`platform/ops/ops-server`): every service's parked entries in one place and the operator's console, on PostgreSQL and Kafka, behind CoSec, with two nodes. They follow the [eShop ordering manifests](../eshop-ordering/README.md); the choices there (internal port, probes, shutdown, node id) hold here too.

| File | What it holds |
| --- | --- |
| `kustomization.yaml` | The set: `kubectl apply -k deploy/ops` |
| `configmap.yaml` | The settings every node shares, as environment variables: the profiles (`postgres,kafka,cosec`), the PostgreSQL URL, the Kafka brokers, the log partitions, OpenTelemetry |
| `secret.yaml` | The PostgreSQL user and password, and the secret CoSec signs its tokens with (`COSEC_JWT_SECRET`). These are example values; replace them |
| `deployment.yaml` | The nodes: the HTTP port (`8088`: the console and the ops routes) and the internal port (`9098`); probes, shutdown, and the OpenTelemetry Operator's annotation |
| `service.yaml` | `ops`, the HTTP service; `ops-cluster`, the headless service of the internal port |
| `networkpolicy.yaml` | Only the CoSec gateway's pods reach the HTTP port, and only the platform's own pods the internal port (decisions D173, D300) |

## Before you apply

1. Build the image, from the repository root, with the console in it:

   ```bash
   pnpm --filter wow-ops-console... build
   ./gradlew :ops-server:installDist
   docker build -t wow-ops-server:local platform/ops/ops-server
   ```

   For a local cluster, load the image into it (`kind load docker-image wow-ops-server:local`). Otherwise use a released image (`ahoowang/wow-ops-server:<version>`) or push yours, and change `image` in `deployment.yaml`.

2. Provide PostgreSQL and Kafka. `configmap.yaml` expects them at `postgres:5432` (database `wow_ops`) and `kafka:9092`. The host creates its tables at startup.

3. Create the Kafka topics the ops service owns. `WOW_KAFKA_TOPICS_DDL=validate` (decision D168): a node does not start while one is missing, or while its command topic does not have exactly `WOW_LOG_PARTITIONS` partitions, and its log names each such topic:

   ```bash
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.ops.commands --partitions 64
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.ops.receipts --partitions 64
   kafka-topics --bootstrap-server kafka:9092 --create --topic wow.ops.parked.state --partitions 64
   ```

   Every service whose ledger the console shows publishes its entries to `wow.ops.commands` (`wow.ops.publish.enabled` on that service); an operator's action goes to that service's own command topic, `wow.<context>.commands`, which it owns. On a cluster for trying it out, set `WOW_KAFKA_TOPICS_DDL=auto` instead.

4. Set `COSEC_JWT_SECRET` to the secret your CoSec signs its access tokens with (HMAC256), and label your CoSec gateway's pods `app.kubernetes.io/component: gateway`, or change the selector in `networkpolicy.yaml` to match them.

## Apply and sign in

```bash
kubectl apply -k deploy/ops
kubectl rollout status deployment/ops
kubectl port-forward service/ops 8088:80
```

Open `http://localhost:8088/`, and sign in with an access token your CoSec issued (the console's sign-in page; a CoSec single sign-on that stored its token in this browser signs the console in too). The service reads the operator from the token:

- **Roles.** `ops.operator` acts on the entries (resume, skip, mark recoverable, apply a retry spec); `ops.viewer` only reads them. A principal with neither reads nothing: both are tenant-level roles (`wow.cosec.tenant-level.roles` in the `cosec` profile), whose scope has no owner.
- **Tenant.** The services' ledgers publish to Wow's default tenant, `(0)`, which a principal of CoSec's default tenant is in.
- **Application.** The console calls as `CoSec-App-Id: ops-console`. The ops service does not use it to decide anything.

A request without a token, or with one the service does not verify, is answered 401; the console then signs out.

## Choices

- **CoSec.** The `cosec` profile runs CoSec's inject mode: the service verifies each token itself and reads the principal from it, without CoSec's own authorization of each path (no policy or permission repository to run). Whether a caller is an operator is the service's to say, by its roles. With CoSec's authorization on (`cosec.authorization.enabled=true`, with CoSec's policy and app role permission repositories) and `wow.cosec.authorizer.enabled=true`, CoSec's policies decide it instead, as the ledger's system commands `wow.ledger.*`.
- **Traces and metrics.** The image carries Wow's OpenTelemetry module, whose spans and meters go through the OpenTelemetry Java agent. The pod asks the [OpenTelemetry Operator](https://opentelemetry.io/docs/kubernetes/operator/automatic/) to inject it (`instrumentation.opentelemetry.io/inject-java`), which needs an `Instrumentation` resource in the namespace. Where nothing injects the agent, the module records into no-ops. `configmap.yaml` names the service and the collector (`OTEL_*`); `wow.opentelemetry.enabled=false` turns the module off.
- **Internal port.** Reached by the service's own nodes only. The services whose entries it shows reach it through Kafka.

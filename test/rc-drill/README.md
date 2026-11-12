# The rc drill: the eShop deployed in a production shape

Before the release candidate, the eShop and Wow's platform services are deployed as a user deploys them: from a release's published artifacts, on a cluster of several nodes in three zones, with the stores replicated and authenticated, behind an ingress and the CoSec gateway, with a metrics pipeline and the documented alerts. Then the drill drives the end-to-end suite through the ingress, while it upgrades, kills, stops, scales and drains. This folder holds that deployment's shape. The script that runs the steps comes with it.

The drill runs twice on one machine: on 10.0.0-beta.2 as the dress rehearsal, then on 10.0.0-beta.3, with the upgrade from beta.2. It runs in a window when nothing else uses the machine's Docker.

## What the machine needs

Everything runs in one kind cluster (`kind.yaml`): a control plane and three workers, each worker a zone of its own. kind's nodes are containers of the Docker VM, and each reports the whole VM as its capacity. So nothing stops the cluster from asking more of the VM than it has; the VM's own size is the bound.

| Resource | Needs | Why |
|---|---|---|
| **Memory of the Docker VM** (OrbStack: Settings › System › Memory limit) | **48 GiB** | The pods request about 28 GiB, and are limited to about 39 GiB. Each Java node grows toward its limit (heap at 75 %). Scaling ordering out and a rolling update's surge add about 2.5 GiB at their peak. The control plane and Calico take about 2 GiB. The machine has 64 GiB; the VM is set to 32 GiB today, too little. |
| **CPUs of the Docker VM** | **16** (all the VM has; the machine has 18) | About 9 cores requested. The load, the faults and the services' recoveries keep 8–12 busy. |
| **Free disk of the Docker VM** | **60 GB** | The images: each worker pulls what it runs, and every node holds the five eShop images, which the drill loads into all of them (about 25 GB). The eShop's build and its image layers (about 5 GB). The volumes (under 5 GB at the drill's data). |
| **Time** | about **5 h** a run | The steps, timed in the drill's plan. Other sessions' Docker work (Testcontainers, the integration tests) is held for the whole run. |

What the pods ask, in the order the platform comes up:

| Part | Pods | CPU request | Memory request / limit |
|---|---|---|---|
| MongoDB (`platform/mongodb`, deploy/'s sizes) | 3 | 1.5 | 3 / 6 GiB |
| Kafka (`platform/kafka`) | 3 | 0.75 | 3.75 / 4.5 GiB |
| Elasticsearch (`platform/elasticsearch`) | 3 | 0.75 | 4.5 / 4.5 GiB |
| ClickHouse | 1 | 0.25 | 1 / 2 GiB |
| Redis, the CoSec gateway | 1 + 2 | 0.55 | 2.1 / 2.25 GiB |
| Traefik, the collector, Prometheus, kube-state-metrics, the operator | 6 | 0.7 | 1.7 / 3.4 GiB |
| The eShop's five services and the platform's three (deploy/'s values) | 16 | 4.5 | 12 / 16 GiB |
| **In all** | 35 | **≈ 9** | **≈ 28 / 39 GiB** |

## Tools

On the machine: `docker` (OrbStack), `kind` and the Calico manifest at the versions of `versions.env`, `kubectl`, `helm` (3.x or 4.x), `openssl`, `jq` and `curl`. A JDK 25 is needed to build the eShop's servers against the published Wow. `curl` and `helm pull` reach the public registries for the release's artifacts.

## What is here

| Path | What it is |
|---|---|
| `kind.yaml` | The cluster: a control plane, three workers in zones `zone-a`, `zone-b` and `zone-c`, Calico instead of kindnet (NetworkPolicy enforced). The ingress's node ports are mapped to `127.0.0.1:30080` (HTTP) and `127.0.0.1:30443` (HTTPS). |
| `versions.env` | The third-party pieces the drill installs by version: kind, Calico, the Traefik chart, the OpenTelemetry Operator chart. The images the manifests name are pinned there, by digest. |
| `platform/` | The platform, in the namespace `wow-rc-drill` (`platform/kustomization.yaml`). Every store is spread over the three zones where it has three members. Every store has its NetworkPolicy. |
| `platform/mongodb/` | `deploy/mongodb`, with the authentication its README asks for: a keyfile, a root user, a user per service with `readWrite` on its own database (`init-users.js`), and `drill-reader` for the verdict's reads. One member per zone. |
| `platform/kafka/` | Three KRaft brokers, which are also the controllers. Every topic (`kafka-topics.yaml`) has a replication factor of 3 and `min.insync.replicas` 2, as Operate › Kafka requires of a command topic; the services validate them. |
| `platform/elasticsearch/` | Three nodes with security on: TLS between the nodes, the catalog's own user and role (`elasticsearch-users.yaml`). The catalog runs its `search` profile on it. |
| `platform/clickhouse/` | The warehouse of the ordering's Sink and the analytics catalog. |
| `platform/gateway/` | The CoSec gateway (two replicas, admitted from the ingress alone) and its Redis (on a volume, `noeviction`). The routes are the GA end-to-end run's. |
| `platform/ingress/` | Traefik (`traefik-values.yaml`), and the Ingress that sends every path to the gateway over HTTP and HTTPS. |
| `platform/observability/` | The OpenTelemetry Operator's values and the Instrumentation that injects the Java agent, as `deploy/`'s values expect. The collector. Prometheus with `alerts.yaml`, one rule per row of Operate › Observability's "What to alert on". kube-state-metrics for the pods' readiness. |
| `platform/probes.yaml` | The boundary's vantage points, `outsider` and `insider`, on the catalog's image. |
| `values/` | What the drill sets on top of each service's generated values and `deploy/<service>/values.yaml`: the catalog's `search` profile, the ordering's `clickhouse` profile, and the end-to-end suite's settings (named as such in each file). The platform's three services take `deploy/`'s values unchanged. |

### Where the drill differs from `deploy/`, and why

`deploy/` is a starting point: its README says what to add before it holds data you keep. The drill adds exactly that, and nothing in `deploy/` changes:

- authentication for MongoDB;
- one member per zone;
- the stores the eShop's profiles need, which `deploy/` leaves to the platform: Kafka, Elasticsearch, ClickHouse, the gateway;
- NetworkPolicies for Kafka, Elasticsearch, ClickHouse and Redis, as Operate › Security says the stores are closed;
- Kafka topics that survive a broker's loss.

What the drill does not add, and its report says so:
- Kafka's authentication and ACLs (Operate › Security asks for ACLs on the command topics);
- TLS on the stores' client ports inside the cluster;
- backups beyond the drill's own backup step.

## The platform

The order, which the drill's script follows:

1. **The cluster.** `kind create cluster --config kind.yaml`, then Calico (`versions.env`), then the namespace `wow-rc-drill`.
2. **The secrets** (below), generated for the run.
3. **The platform's base.** `kubectl apply -k platform`; wait for every StatefulSet to roll out and for `job/mongodb-init` to complete.
4. **The jobs.** `platform/kafka/kafka-topics.yaml`, once Kafka answers. `platform/elasticsearch/elasticsearch-users.yaml`, once Elasticsearch answers. Wait for both to complete.
5. **The telemetry.** The OpenTelemetry Operator from its chart (`platform/observability/otel-operator-values.yaml`, namespace `opentelemetry`), then `platform/observability/instrumentation.yaml`.
6. **The gateway.** Its documents (`.github/ga-e2e/gateway/documents`), published into Redis through a port-forward, as the GA end-to-end run publishes them. Then `platform/gateway/gateway.yaml`, then Traefik from its chart (`platform/ingress/traefik-values.yaml`, namespace `traefik`), then `platform/ingress/ingress.yaml`.
7. **The probes.** `platform/probes.yaml`, with the run's version in place of `DRILL_VERSION`.
8. **The services.** Each with the chart of the release, by version: `helm install <service> oci://ghcr.io/ahoo-wang/wow-service --version <version>`, with, in order:
   - the values its build generates (`generateWowHelmValues`);
   - `deploy/<service>/values.yaml`;
   - `values/<service>.yaml` when there is one;
   - for the eShop's services, `--set image.tag=<version>`.

   The platform's three take the published images at the chart's version.

### Secrets

None is committed. The drill generates each for the run (random values, hex), creates them in `wow-rc-drill` and keeps them only in the run's evidence folder:

| Secret | Keys | Read by |
|---|---|---|
| `mongodb-auth` | `keyfile`, `username`, `password` | The members (keyfile, root user) and `mongodb-init` |
| `mongodb-users` | `ESHOP_CATALOG`, `ESHOP_BASKET`, `ESHOP_ORDERING`, `ESHOP_INVENTORY`, `ESHOP_PAYMENT`, `OPS`, `VIEW_STORE`, `ANALYTICS_CATALOG`, `DRILL_READER` | `init-users.js`; `mongo-shell` (`DRILL_READER`) |
| `<service>-service-mongo` (the name each service's values already read) | `SPRING_MONGODB_URI`, with the service's user and password, `authSource=admin`, `replicaSet=rs0`, `w=majority`, and no database in its path (the host would take a path's database as `wow.mongo.database`; each service names its own). Beside it: `SPRING_ELASTICSEARCH_PASSWORD` (catalog), and `WOW_CLICKHOUSE_PASSWORD` (ordering, analytics catalog) | The service |
| `elasticsearch-certs` | `ca.crt`, `tls.crt`, `tls.key`: the drill's CA and one transport certificate for every node | Elasticsearch |
| `elasticsearch-credentials` | `password` (the `elastic` superuser), `eshop-catalog` | Elasticsearch, `elasticsearch-users` |
| `clickhouse-credentials` | `password` (the `wow` account) | ClickHouse |
| `cosec-jwt` | `COSEC_JWT_SECRET` | The gateway; the drill signs its callers' tokens with it |
| `drill-ingress-tls` | `tls.crt`, `tls.key`: a certificate for `localhost` signed by the drill's CA | Traefik |

## Not covered here

- **The eShop's web.** A Vite development server that sends development headers, with no CoSec sign-in; it is not deployable behind the gateway. The drill reaches the ops and analytics consoles, which the platform's images serve.
- **PostgreSQL and a sharded MongoDB.** The GA end-to-end run's postgres arm covers PostgreSQL every night. `mongo-cluster.yml` covers the sharded cluster with the MongoDB backend's tests.

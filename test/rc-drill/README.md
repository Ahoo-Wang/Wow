# The rc drill: the eShop deployed in a production shape

Before the release candidate, the eShop and Wow's platform services are deployed as a user deploys them: from a release's published artifacts, on a cluster of several nodes in three zones, with the stores replicated and authenticated, behind an ingress and the CoSec gateway, with a metrics pipeline and the documented alerts. Then the drill drives the end-to-end suite through the ingress, while it upgrades, kills, stops, scales and drains. This folder holds that deployment's shape. The script that runs the steps comes with it.

The drill runs twice, on one machine, each about five hours in a window when nothing else uses the machine's Docker or Gradle. Each installs a previous release from its published chart and images, then upgrades to the new one (step 4): under load where the two run side by side, or by the upgrade page's quiesce steps (stop, catch up, upgrade) where the page asks for them, and then rolls the new version's pods under load. The two versions are the run's arguments, so no file here names them: for 10.0.0-beta.10, the previous one is 10.0.0-beta.9.

1. **The rehearsal.** The platform and the drill's cluster test (`RcDrillTest`) run on a cluster for the first time, and may stop on things that are not Wow's: the drill's own files and scripts, the platform's pieces. What it finds is fixed by commits before the second run.
2. **The run that counts.** Its report is the drill's result. The report lists what the rehearsal found and what was changed for it, so the result is read with its history.

There is no run on the previous release alone. What can be proven without a cluster is proven before the rehearsal: `tools`, `artifacts`, `compare`, the eShop's build against the published Wow, and the renders (`render`).

## What the machine needs

Everything runs in one kind cluster (`kind.yaml`): a control plane and three workers, each worker a zone of its own. kind's nodes are containers of the Docker VM, and each reports the whole VM as its capacity. So nothing stops the cluster from asking more of the VM than it has; the VM's own size is the bound.

| Resource | Needs | Why |
|---|---|---|
| **Memory of the Docker VM** (OrbStack: Settings › System › Memory limit) | **48 GiB** | The pods request about 28 GiB, and are limited to about 40 GiB. Each Java node grows toward its limit (heap at 75 %). Scaling ordering out and a rolling update's surge add about 2.5 GiB at their peak. The control plane and Calico take about 2 GiB. The machine has 64 GiB, and the VM 48 GiB (OrbStack's `memory_mib` 49152), which it reports as about 47 GiB. |
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
| Traefik, the collector, Prometheus, kube-state-metrics, the operator | 6 | 0.7 | 1.95 / 4.4 GiB |
| The eShop's five services and the platform's three (deploy/'s values) | 16 | 4.5 | 12 / 16 GiB |
| **In all** | 35 | **≈ 9** | **≈ 28 / 40 GiB** |

## Tools

The drill was written and checked against these versions. Install matching ones:

| Tool | Version | Note |
|---|---|---|
| `helm` | **4.3.1** | The version CI installs the chart with (ci.yml's `helm-kind`, ga-e2e.yml). CI's `helm` job also renders, lints and unit-tests the chart under 3.22.1. The drill's values and the third-party charts were rendered under 4.3.1 only. |
| `kind` | **v0.33.0** | As CI's kind clusters. |
| Node image | `kindest/node:v1.37.0` | kind v0.33.0's own, pinned by digest in `kind.yaml`: Kubernetes 1.37.0. |
| `kubectl` | **1.36 to 1.38** | kubectl supports one minor version of skew from the server, 1.37. On this machine: Homebrew's `kubernetes-cli` 1.37.2 at `/opt/homebrew/bin/kubectl`, and OrbStack's own v1.33.9 at `/usr/local/bin/kubectl`, which a shell started before the path changed still finds first. The drill resolves kubectl once (`DRILL_KUBECTL`, else the first on the path), and `drill.sh tools` refuses one outside 1.36 to 1.38: run it with `DRILL_KUBECTL=/opt/homebrew/bin/kubectl` to be sure. |
| Calico | v3.32.2 | `versions.env`, checked by SHA-256. |
| Docker | OrbStack | Sized as above. |
| JDK 25 | **25** | What runs, by the owner's rule (everything that runs, runs on JDK 25). The images run on it: deploy/'s Dockerfiles take Alibaba Dragonwell 25 (`dragonwell:25-ubuntu`, pinned by digest). The cluster test's JVM runs on it (`-Pwow.test.java=25`), and so does every Gradle the drill launches (`DRILL_JAVA_HOME`, else `java_home -v 25`). On this machine: `/Library/Java/JavaVirtualMachines/temurin-25.jdk`. `drill.sh tools` refuses the run without it. |
| JDK 21 | **21** | The build's toolchain, as CI's: it compiles the eShop and the platform's generated values at the tag, and Gradle finds it as a toolchain (it need not be `JAVA_HOME`). On this machine: `/Library/Java/JavaVirtualMachines/temurin-21.jdk`, also the shell's default `JAVA_HOME`. |

Plus `openssl`, `jq` and `curl`. `curl` and `helm pull` reach the public registries for the release's artifacts. A version nothing published (below) also needs `pnpm` and Node.js 22.12 or later, which build the consoles the platform's images serve.

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
| `platform/gateway/` | The CoSec gateway (two replicas, admitted from the ingress alone) and its Redis (on a volume, `noeviction`). The routes are the GA end-to-end run's. Its image is the one the installed Wow version's own GA end-to-end run deploys, pinned by digest in that version's `.github/ga-e2e/gateway/gateway.yaml`, so a version runs behind the CoSec it was released with; an upgrade moves the gateway after the services, in CoSec's order. |
| `platform/ingress/` | Traefik (`traefik-values.yaml`), and the Ingress that sends every path to the gateway over HTTP and HTTPS. |
| `platform/observability/` | The OpenTelemetry Operator's values and the Instrumentation that injects the Java agent, as `deploy/`'s values expect. The collector. Prometheus with `alerts.yaml`, one rule per row of Operate › Observability's "What to alert on". kube-state-metrics for the pods' readiness. |
| `platform/probes.yaml` | The boundary's vantage points, `outsider` and `insider`, on the catalog's image. |
| `eshop-build.sh`, `published-wow.init.gradle.kts` | The eShop's servers built from a release's source against the Wow it published on Maven Central ("Running it"). |
| `values/` | What the drill sets on top of each service's generated values and `deploy/<service>/values.yaml`: the catalog's `search` profile, the ordering's `clickhouse` profile, and the end-to-end suite's settings (named as such in each file). Every service, the platform's three included, also takes `pod-security-workaround.yaml` when its chart names no user (step 8): a user of 10001 for the pods, the workaround for the OpenTelemetry agent's init container under the chart's `runAsNonRoot` (the file says why). Otherwise the platform's three take `deploy/`'s values unchanged. |

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
4. **The jobs.** The topics, as the installed version's own drill creates them (its `test/rc-drill/platform/kafka/kafka-topics.yaml`, else this one), once Kafka answers. `platform/elasticsearch/elasticsearch-users.yaml`, once Elasticsearch answers. Wait for both to complete.
5. **The telemetry.** The OpenTelemetry Operator from its chart (`platform/observability/otel-operator-values.yaml`, namespace `opentelemetry`), then `platform/observability/instrumentation.yaml`.
6. **The gateway.** Its documents (the version's `.github/ga-e2e/gateway/documents`), published into Redis through a port-forward by the `cosec-migrate` of the CoSec that version names, as the GA end-to-end run publishes them. Then `platform/gateway/gateway.yaml` on that version's gateway image, then Traefik from its chart (`platform/ingress/traefik-values.yaml`, namespace `traefik`), then `platform/ingress/ingress.yaml`.
7. **The probes.** `platform/probes.yaml`, with the run's version in place of `DRILL_VERSION`.
8. **The services.** Each with the chart of the release, by version: `helm install <service> oci://ghcr.io/ahoo-wang/wow-service --version <version>`, with, in order:
   - the values its build generates (`generateWowHelmValues`);
   - `deploy/<service>/values.yaml`;
   - `values/pod-security-workaround.yaml`, only when the chart of that version sets no `podSecurityContext.runAsUser` (10.0.0-beta.10 and earlier): read from the chart step 0 checked, and logged for each install, so a chart that names its user is proved by its own default;
   - `values/<service>.yaml` when there is one;
   - for the eShop's services, `--set image.tag=<version>`.

   The platform's three take the published images at the chart's version (a version nothing published: the images the drill built). Building their generated values compiles the platform's servers, and Wow, at the release's tag (step 2's `build`): it reads their contracts for the values, and none of what it builds is deployed. Only the eShop's five services run images the drill built, from the tag against the published Wow.

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

## Running it

`drill.sh` runs the steps, one command per step, or all of them in order. It dispatches; the steps are in `lib/` by what they are: `common.sh` (the settings, the helpers, the evidence), `platform.sh`, `release.sh` (artifacts, build, install, upgrade), `faults.sh` and `verdict.sh`.

```bash
DRILL_KUBECTL=/opt/homebrew/bin/kubectl DRILL_EVIDENCE=build/rc-drill/beta.10 DRILL_UPGRADE=rolling test/rc-drill/drill.sh run 10.0.0-beta.10 10.0.0-beta.9
```

`drill.sh render <version>` renders every service's release as `install` would make it (`helm template`, the chart's schema included) and the platform's base, into `render/<version>/`, once `build` has generated the values.

Both runs go one step at a time, each command on its own (`drill.sh tools`, then `drill.sh artifacts 10.0.0-beta.10`, and so on), so a stop is seen when it happens. `tools` and `artifacts` stop the run before any cluster exists.

The window is the machine's batch lock (`test/benchmarks/harness/lock.sh`). Take it first, with `bench.sh lock --owner=<name> --wait`, then export `WOW_BENCH_OWNER=<name>` in the shell that runs the steps. A step, run on its own or re-run by hand, is `drill.sh step <name> <command> [<argument>...]`, as `run` names it: for example `drill.sh step 3-e2e e2e functional`. It is timed into `steps.tsv` like the others. `step` and `run` never wait for the lock, as `quiet.sh` would. Outside the window, or in a shell whose `WOW_BENCH_OWNER` does not name the lock's holder, they stop at once and say which.

With a previous version, the run installs that version first, then upgrades to the new one (step 4).
- `DRILL_UPGRADE` is the drill's decision, taken from `drill.sh compare <previous> <version>`, which lists what changed between the two in the stores, the cluster channel, the configuration and the deployment (the chart, `deploy/`, the GA run's gateway and topics), and from Operate › Upgrades:
  - `rolling` when nothing there stops a running deployment from crossing under load;
  - `quiesce` when the upgrade page asks for its quiesce steps: the upgrade is made not under load (`drill.sh quiesce <previous> <version>`, below), then the new version's functional suite judges what the old one left (`4-functional`), then the new version's pods are rolled under load (`4-restart`);
  - `restart` when neither can cross: a fresh install of the new version, then a rolling restart at that version under load.
- The run refuses to guess.

**The quiesce upgrade** (`quiesce`) follows Operate › Upgrades, "Published streams on Kafka", for every service at once, since each eShop service both publishes and consumes, and CoSec's order from "From a 10.0.0 pre-release":
1. The commands stop: the gateway is scaled to none, so no caller reaches a service. A service's own schedules and timers still fire, and the next step waits for what they send too.
2. Every subscription catches up, with nothing parked: for every partition `wow.subscription.backlog` 0 and `wow.subscription.progress` 1, and `wow.subscription.parked.aggregates` 0, over the last minute, read from Prometheus. Each read is logged; it waits at most 15 minutes.
3. Every state topic with fewer partitions than the log's 64 is given 64 (`kafka-topics.sh --alter`).
4. CoSec's stored patterns are checked with the new version's `cosec-migrate check-patterns`, while the old gateway still runs. A pattern it names stops the upgrade.
5. The catalog's nodes are stopped, and its Elasticsearch indices that are no alias's are deleted: Operate › Elasticsearch says a store refuses an index an earlier pre-release created, to be deleted and rebuilt. The indices and aliases are logged before and after.
6. The services are upgraded in their waves, each until its rollout is done. Then the gateway, on the new version's image. Then every subscription catches up again.

**Evidence.** Each step is timed in the evidence folder (`steps.tsv`, `steps/<step>.log`), and every pod's logs of the step, its current and previous containers', are kept in `logs/<step>/` before the next step starts, so a later reinstall or drain cannot take them.

A pod a fault deletes has no logs left to keep, so the services' agents also send their logs to the collector. The collector writes each one to its own output, one OTLP JSON line per batch, each record naming its `k8s.pod.name`. A deleted pod's lines are then in the collector's log of that step, `logs/<step>/otel-collector-*.collector.log`. The kubelet rotates that file at 10Mi, and `kubectl logs` reads only the current one, so every one of the collector's log files on its node written during the step is kept too, rotated ones included, in `logs/<step>/otel-collector-*.node-logs/`.

Beside the logs:
- the artifacts' check;
- the comparison;
- the end-to-end reports (`e2e/<label>/`);
- the alerts' history (`alerts.json`);
- the backup's dump;
- the diagnostics;
- the run's secrets (`secrets/`).

The folder stays on the machine. `drill.sh teardown` deletes the cluster.

**The two pieces `drill.sh` calls:**

- **`eshop-build.sh <source> <version>`.** It builds the eShop's five servers from a release's source (a worktree of its tag) against the Wow that version published on Maven Central, not the source's own modules:
  - `installDist`, which the images' Dockerfiles take;
  - `generateWowHelmValues`, which writes `build/wow/helm/values.yaml`.

  This is what "built from the tag against the published Wow" means: the release's own source and build, with only the Wow it depends on taken from Maven Central. The tag's files cannot hold a switch added after it, so the substitution comes from outside the tag. It runs the release's own build with `published-wow.init.gradle.kts`, so the tag's files stay as they were tagged. The eShop declares Wow by its coordinates, and the root build substitutes each with its project; the init script adds a later rule, for the eShop's projects alone, that sends every `me.ahoo.wow` module back to the published version: their classpaths, the BOM, the compiler the Gradle plugin adds, and the platform of third-party versions the root build adds to every classpath (`:wow-dependencies`). Only the eShop's own projects are resolved as projects. Then it proves where Wow came from: every `wow-*-<version>.jar` in each distribution must have the SHA-1 that Maven Central publishes for it, and no other version of a Wow jar may be there.
- **The cluster test driven from outside** (`:eshop-e2e:clusterTest`, its `RcDrillTest`, which runs alone when `ga.scenarios` is set and never in the GA end-to-end run). Its `kubectl` acts in the kind context's namespace, which `cluster` sets. Besides `ga.gateway`, `ga.secret`, `ga.backend`, `ga.partitions` and `ga.report`, it takes:
  - `ga.state`: the drill's record, kept across its runs (the stock received, the checkouts, the numbers answered, the orders shipped), so every run's verdict judges the whole drill so far;
  - `ga.mongo-user`: the user its reads of MongoDB take, `drill-reader`. Its password is never in a file of the tree: the drill generates it for the run, the secret `mongodb-users` gives it to `mongo-shell`'s environment, and the reads take it from there;
  - `ga.scenarios`: what it runs:
    - `functional`: step 3;
    - `owner-deletion`: step 5's first half;
    - `ops`: step 9;
    - `verdict`: the invariants alone;
    - `load`: full-chain checkouts by `ga.load.clients` buyers at once (4), a probe measuring every failure, until a stop file. With `ga.load.stop-file`, `ga.load.bound` (the bound of the failures' window) and `load.started` in its report folder once its first checkout is answered, so `drill.sh` starts an operation only under load.

    Every run then checks that no Wow container restarted and every Wow pod is ready, and judges the invariants over the whole drill.

    **The verdict's rule.** It judges a chain from its origin, the basket's checkouts, not from one hop in: a checkout's saga can still be submitting its order after the load stops, for tens of seconds across a partition hand-off. Before it judges, within one bound (5 minutes), it waits twice:
    1. until every `CheckoutBasket` in the basket's log has its order in the ordering's log, matched by the order's causation;
    2. until the order list (the ordering's snapshot query, which lags behind that log) holds every order of the log, and every order is settled (paid, shipped or cancelled), which includes its stock and payment legs.

    It reads the stock only after both waits. Nothing is dropped silently: a checkout without an order once the bound has passed, an order the list lacks, or a state read that answers nothing each fails the verdict by name. It prints how long each wait took, and the counts it judged: the orders listed, the orders in the log, the checkouts in the basket's log, and the orders of any other origin.

## A version nothing published

The new version of a rehearsal may be a commit of `main` that no release published yet: the run names it by its full SHA (`drill.sh run <sha> 10.0.0-beta.21`). Its source is a worktree at that commit, and the drill builds from it what a release would publish:
- **Its name.** Its images, chart and folders take the major, minor and patch its source builds, then `main` and the commit's first twelve digits, such as `10.0.0-main.649086cf9eb9`: a SemVer the chart takes as its version, and a Docker tag. The jars keep the version the source's `gradle.properties` names.
- **The eShop's servers.** `eshop-build.sh <source> <sha>` builds them as the repository does, the eShop's Wow being the source's own modules, with no init script. It proves the source is at the commit with nothing changed, and that every Wow jar in each distribution is the one its module built in the source (`build/libs`), byte for byte.
- **The platform's three images.** Built as `image-deploy.yml` builds a release's: the consoles (`pnpm`), each server's `installDist`, then its image, for this machine's platform alone, and loaded into every node with the eShop's.
- **The chart.** The source's `deploy/helm/wow-service`, packaged with the name above as its version and `appVersion` (`artifacts/charts/local/<name>/`), and installed from that package.

`artifacts` has nothing to check for such a version: the run skips it, and `build` stops if a piece is missing.

## Not covered here

- **The eShop's web.** A Vite development server that sends development headers, with no CoSec sign-in; it is not deployable behind the gateway. The drill reaches the ops and analytics consoles, which the platform's images serve.
- **PostgreSQL and a sharded MongoDB.** The GA end-to-end run's postgres arm covers PostgreSQL every night. `mongo-cluster.yml` covers the sharded cluster with the MongoDB backend's tests.

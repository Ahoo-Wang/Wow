# shellcheck shell=bash
#
# Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#      http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# The rehearsal's kind arm (MIGRATE_IN=kind), sourced by rehearse.sh over its runner functions: the same steps, with
# the stores in the kind cluster the workflow created (stores.yaml) and every migrate-data command run by the
# wow-migrate-data image inside it, as deploy/migrate-data runs it: a Job per command (migrate/ renders the recipe's
# Job), the tail a one-replica Deployment, the configuration and the map in a ConfigMap, the password in a Secret, the
# checkpoints kept in the target (target:rehearsal), the classes of both V10 hosts copied from their images by init
# containers and the Manifests read from them, and the recipe's NetworkPolicy. The 9.x example-server, the V10 hosts
# and the driver stay on the runner, on the stores' NodePorts. where_timings then times the same import by where it
# runs.
#
# Environment, besides rehearse.sh's: the images wow-migrate-data:local, wow-migration-rehearsal:local and
# wow-view-store-server:local, loaded into the cluster; MIGRATE_IMAGE, the migrate-data image a Job runs
# (wow-migrate-data:local); and for bench_imports and cutover_bench, wow-migrate-data:baseline.

KIND_DIR="$REHEARSAL/kind"
ROOT="$(cd "$REHEARSAL/../../.." && pwd)"
KIND_JOBS=0
PF_MONGO_PORT=37017
PF_POSTGRES_PORT=35432

stores_up() {
  kubectl create configmap rehearsal-scripts --from-file="$REHEARSAL/seed-compensation.js" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl create secret generic migrate-data --from-literal=WOW_PG_PASSWORD=postgres \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl apply -f "$KIND_DIR/stores.yaml" -f "$ROOT/deploy/migrate-data/networkpolicy.yaml" >/dev/null
  kubectl rollout status deployment/mongo --timeout=300s
  kubectl rollout status deployment/postgres --timeout=300s
  # The recipe's Job and tail as the arm runs them; each run names its Job and gives its command.
  kubectl kustomize "$KIND_DIR/migrate" | kubectl create --dry-run=client -o json -f - >"$WORK/kind-templates.json"
  mkdir -p "$WORK/runner"
}
# The workflow deletes the cluster.
stores_down() { :; }
mongosh_run() { kubectl exec deploy/mongo -- mongosh --quiet "$@"; }
createdb() { kubectl exec deploy/postgres -- createdb -U postgres "$1"; }
psql_run() { kubectl exec deploy/postgres -- psql -U postgres -At "$@"; }

# config_json DB MONGO POSTGRES: migrate-data's configuration for the V10 log in database DB, the stores at MONGO and
# POSTGRES (host:port), as the runner arm's (rehearse.sh write_config) but for the cluster: the checkpoints in the
# target, the password from the Secret, the classes where the init containers copy them, and no manifests: the tool
# reads them from those classes.
config_json() {
  # The settings only the sweeps' runs carry, each its own line: quotes inside \${…:+…} do not survive a here-document.
  local parallelism="" groups="" pool=""
  if [ -n "${READ_PARALLELISM:-}" ]; then
    parallelism=$'\n  "readParallelism": '"$READ_PARALLELISM,"
  fi
  if [ -n "${IMPORT_CONCURRENCY:-}" ]; then
    groups=$'\n  "concurrency": '"$IMPORT_CONCURRENCY,"
    pool=$',\n    "poolSize": '"$((IMPORT_CONCURRENCY * 2))"
  fi
  cat <<EOF
{
  "map": "migration-map.json",
  "checkpoint": "target:rehearsal",
  "source": { "uri": "mongodb://$2", "database": "wow_example_db", "createCommitTimeIndex": true },
  "target": {
    "type": "postgres",
    "jdbcUrl": "jdbc:postgresql://$3/$1",
    "username": "postgres",
    "password": "env:WOW_PG_PASSWORD"$pool
  },
  "partitions": { "example": 64, "view-store": 64 },
  "classpath": ["/service/migration-rehearsal", "/service/view-store-server"],
  "compensation": { "database": "compensation_db", "service": "example-service" },
  "lookbackMillis": 20000,
  "pollIntervalMillis": 500,$parallelism$groups
  "final": { "lookbackMillis": 60000, "maxTailAgeMillis": 600000 }
}
EOF
}

# write_config DB: the configuration of database DB for the Jobs, into the ConfigMap with the map and the others.
write_config() {
  local file args=()
  cp "$REHEARSAL/migration-map.json" "$WORK/migration-map.json"
  config_json "$1" mongo:27017 postgres:5432 >"$WORK/migrate-data-$1.json"
  for file in "$WORK/migration-map.json" "$WORK"/migrate-data-*.json; do args+=("--from-file=$file"); done
  kubectl create configmap migrate-data-config "${args[@]}" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
}

# render KIND NAME ARGS...: the arm's Job or Deployment named NAME, its container running ARGS.
render() {
  python3 - "$WORK/kind-templates.json" "$@" <<'PY'
import json, os, sys
templates, kind, name, args = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
# kubectl writes the objects one after the other.
text, decoder, position, items = open(templates).read(), json.JSONDecoder(), 0, []
while text[position:].strip():
    item, position = decoder.raw_decode(text, len(text) - len(text[position:].lstrip()))
    items.append(item)
item = next(item for item in items if item["kind"] == kind)
if kind == "Job":
    item["metadata"]["name"] = name
container = item["spec"]["template"]["spec"]["containers"][0]
container["args"] = args
container["image"] = os.environ.get("MIGRATE_IMAGE") or container["image"]
print(json.dumps(item))
PY
}

# container_state POD [FIELD]: the state of POD's migrate-data container, or its FIELD (a jsonpath under it).
container_state() {
  kubectl get pod "$1" -o jsonpath="{.status.containerStatuses[?(@.name==\"migrate-data\")].state${2:-}}" 2>/dev/null
}

# kind_job ARGS...: one migrate-data run as a Job of the cluster: prints its log, then its container's own time as a
# JSON line (the step's data), and returns its exit code. Kubernetes records the container's times to the second.
kind_job() {
  KIND_JOBS=$((KIND_JOBS + 1))
  local name="migrate-data-$KIND_JOBS-$1" pod="" state="" code
  if ! render Job "$name" "$@" | kubectl create -f - >/dev/null; then
    echo "The Job $name could not be created."
    return 1
  fi
  for _ in $(seq 1 600); do
    pod="$(kubectl get pods -l "job-name=$name" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
    state="$([ -n "$pod" ] && container_state "$pod" || true)"
    case "$state" in *running* | *terminated*) break ;; esac
    sleep 0.5
  done
  case "$state" in
    *running* | *terminated*) ;;
    *)
      echo "The Job $name did not start:"
      kubectl describe job "$name"
      [ -n "$pod" ] && kubectl describe pod "$pod"
      return 1
      ;;
  esac
  kubectl logs -f "$pod" -c migrate-data
  for _ in $(seq 1 120); do
    code="$(container_state "$pod" .terminated.exitCode)"
    [ -n "$code" ] && break
    sleep 0.5
  done
  python3 - "$(container_state "$pod" .terminated.startedAt)" "$(container_state "$pod" .terminated.finishedAt)" <<'PY'
import datetime, json, sys
started, finished = (datetime.datetime.fromisoformat(value.replace("Z", "+00:00")) for value in sys.argv[1:])
print(json.dumps({"containerMillis": int((finished - started).total_seconds() * 1000)}))
PY
  return "${code:-1}"
}

migrate() { kind_job "$1" --config "/config/migrate-data-$TARGET_DB.json" "${@:2}"; }

# The tail, a Deployment of one replica (deploy/migrate-data/tail) on TARGET_DB, its log followed into $WORK/tail.log.
tail_start() {
  render Deployment migrate-data-tail tail --config "/config/migrate-data-$TARGET_DB.json" | kubectl apply -f - >/dev/null
  kubectl rollout status deployment/migrate-data-tail --timeout=300s >/dev/null
  kubectl logs -f deployment/migrate-data-tail -c migrate-data >"$WORK/tail.log" 2>&1 &
  echo $! >"$WORK/tail-log.pid"
}
# Deleting it stops the tail after its current pass (SIGTERM), which releases its lease.
tail_stop() {
  kubectl delete deployment migrate-data-tail --ignore-not-found --wait=true >/dev/null 2>&1 || true
  kubectl wait --for=delete pod -l app.kubernetes.io/component=tail --timeout=180s >/dev/null 2>&1 || true
  stop_pid tail-log
}

# runner_config DB MONGO POSTGRES: the Jobs' configuration of database DB for a run on the runner, the stores at MONGO
# and POSTGRES, into $WORK/runner.
runner_config() {
  cp "$REHEARSAL/migration-map.json" "$WORK/runner/migration-map.json"
  config_json "$@" >"$WORK/runner/migrate-data-$1.json"
}

# runner_import DB: the import into database DB by the same image on the runner, on the host's network, with the same
# heap and the classes of the runner's own installations; prints the container's own time as a JSON line.
runner_import() {
  local container="migrate-data-runner-$1" code
  docker run --name "$container" --network host -e JAVA_OPTS=-Xmx2g -e WOW_PG_PASSWORD=postgres \
    -v "$WORK/runner:/config:ro" -v "$V10_HOME/lib:/service/migration-rehearsal:ro" \
    -v "$VIEWS_HOME/lib:/service/view-store-server:ro" \
    wow-migrate-data:local import --config "/config/migrate-data-$1.json"
  code=$?
  python3 - "$(docker inspect -f '{{.State.StartedAt}}' "$container")" "$(docker inspect -f '{{.State.FinishedAt}}' "$container")" <<'PY'
import datetime, json, re, sys
def parse(value):
    # Docker writes nanoseconds; Python reads microseconds.
    return datetime.datetime.fromisoformat(re.sub(r"(\.\d{6})\d*", r"\1", value).replace("Z", "+00:00"))
started, finished = (parse(value) for value in sys.argv[1:])
print(json.dumps({"containerMillis": int((finished - started).total_seconds() * 1000)}))
PY
  docker rm "$container" >/dev/null
  return "$code"
}

# port_forward start|stop: kubectl port-forward to both stores on the runner's PF_* ports.
port_forward() {
  if [ "$1" = stop ]; then
    stop_pid forward-mongo
    stop_pid forward-postgres
    return 0
  fi
  kubectl port-forward svc/mongo "$PF_MONGO_PORT:27017" >"$WORK/forward-mongo.log" 2>&1 &
  echo $! >"$WORK/forward-mongo.pid"
  kubectl port-forward svc/postgres "$PF_POSTGRES_PORT:5432" >"$WORK/forward-postgres.log" 2>&1 &
  echo $! >"$WORK/forward-postgres.pid"
  for _ in $(seq 1 60); do
    (exec 3<>"/dev/tcp/127.0.0.1/$PF_MONGO_PORT") 2>/dev/null && (exec 3<>"/dev/tcp/127.0.0.1/$PF_POSTGRES_PORT") 2>/dev/null &&
      return 0
    sleep 0.5
  done
  echo "The port-forwards did not come up" >&2
  return 1
}

# timed_import STEP WHERE DB: the full import into the fresh database DB, run WHERE: job (in the cluster), runner (the
# image on the runner, the stores on their NodePorts) or port-forward (the same, through kubectl port-forward).
timed_import() {
  local name="$1" where="$2" db="$3"
  createdb "$db"
  case "$where" in
    job)
      write_config "$db"
      step "$name" 0 kind_job import --config "/config/migrate-data-$db.json"
      ;;
    runner)
      runner_config "$db" "localhost:$MONGO_PORT" "localhost:$POSTGRES_PORT"
      step "$name" 0 runner_import "$db"
      ;;
    port-forward)
      port_forward start
      runner_config "$db" "localhost:$PF_MONGO_PORT" "localhost:$PF_POSTGRES_PORT"
      step "$name" 0 runner_import "$db"
      port_forward stop
      ;;
  esac
}

# where_timings: the same full import of the 9.x store (9.x stopped), each into a fresh database, by where it runs:
# in the cluster (A) and on the runner (B), alternating A B B A, so the two runs of one arm give the noise band
# (D392 ③); then once through kubectl port-forward, the way users reach a cluster's stores from outside it.
where_timings() {
  timed_import timing-job-1 job timing_job_1
  timed_import timing-runner-1 runner timing_runner_1
  timed_import timing-runner-2 runner timing_runner_2
  timed_import timing-job-2 job timing_job_2
  timed_import timing-port-forward port-forward timing_port_forward
}

# bench_imports: the import of the seeded 9.x store (9.x stopped) by two migrate-data images, the baseline
# (wow-migrate-data:baseline, A) and the candidate (wow-migrate-data:local, B), each as a Job into a fresh database,
# alternating A B B A (D392 ③), so each image's two runs give its noise band. Each database is dropped once its run
# is timed, which keeps a large store within the runner's disk. The Jobs' logs hold each aggregate's time and where its
# workers spent it (ImportPhases).
bench_imports() {
  local run=0 arm image db ranges
  mongo_load_start idle
  sleep 30
  mongo_load_stop idle
  for arm in A B B A; do
    run=$((run + 1))
    image=wow-migrate-data:local
    [ "$arm" = A ] && image=wow-migrate-data:baseline
    db="bench_${run}_$(tr '[:upper:]' '[:lower:]' <<<"$arm")"
    createdb "$db"
    write_config "$db"
    export MIGRATE_IMAGE="$image"
    mongo_load_start "bench-$run-$arm"
    step "bench-$run-$arm" 0 kind_job import --config "/config/migrate-data-$db.json"
    mongo_load_stop "bench-$run-$arm"
    unset MIGRATE_IMAGE
    kubectl exec deploy/postgres -- dropdb -U postgres "$db"
  done
  # The candidate's import walking 1, 2, 4 and 8 ranges of ids at once (readParallelism), each with 9.x's MongoDB load:
  # the baseline image may not know the setting, so only these runs' configurations carry it.
  for ranges in ${READ_SWEEP:-1 2 4 8}; do
    db="sweep_$ranges"
    createdb "$db"
    READ_PARALLELISM="$ranges" write_config "$db"
    export MIGRATE_IMAGE=wow-migrate-data:local
    mongo_load_start "sweep-$ranges"
    step "sweep-$ranges" 0 kind_job import --config "/config/migrate-data-$db.json"
    mongo_load_stop "sweep-$ranges"
    unset MIGRATE_IMAGE
    kubectl exec deploy/postgres -- dropdb -U postgres "$db"
  done
  # Then 2 ranges with more groups appended at once (concurrency) and a pool of twice as many connections.
  for groups in ${CONCURRENCY_SWEEP:-16 32}; do
    db="groups_$groups"
    createdb "$db"
    READ_PARALLELISM=2 IMPORT_CONCURRENCY="$groups" write_config "$db"
    export MIGRATE_IMAGE=wow-migrate-data:local
    mongo_load_start "groups-$groups"
    step "groups-$groups" 0 kind_job import --config "/config/migrate-data-$db.json"
    mongo_load_stop "groups-$groups"
    unset MIGRATE_IMAGE
    kubectl exec deploy/postgres -- dropdb -U postgres "$db"
  done
}

# mongo_load_start NAME: samples 9.x's MongoDB while step NAME runs: mongod's CPU time and operation counters now, and
# a probe in its pod that reads one order's streams by id every 200 ms, as 9.x does to serve a command, each read's
# milliseconds a line. mongo_load_stop NAME ends it and records the load as the step load-NAME (load.py).
mongo_load_start() {
  kubectl exec deploy/mongo -- cat /proc/1/stat | awk '{print $14 + $15}' >"$WORK/load-$1.cpu"
  mongosh_run wow_example_db --eval 'JSON.stringify(db.serverStatus().opcounters)' >"$WORK/load-$1.ops"
  now_ms >"$WORK/load-$1.at"
  kubectl exec deploy/mongo -- mongosh --quiet wow_example_db --eval "$(sed "s/@RUN@/$1/" "$KIND_DIR/probe.js")" \
    >"$WORK/load-$1.probe" 2>&1 &
  echo $! >"$WORK/load-$1.pid"
}

mongo_load_stop() {
  kubectl exec deploy/mongo -- cat /proc/1/stat | awk '{print $14 + $15}' >>"$WORK/load-$1.cpu"
  mongosh_run wow_example_db --eval 'JSON.stringify(db.serverStatus().opcounters)' >>"$WORK/load-$1.ops"
  now_ms >>"$WORK/load-$1.at"
  mongosh_run --eval "db.getSiblingDB('rehearsal_probe').stop.insertOne({ run: '$1' })" >/dev/null
  wait "$(cat "$WORK/load-$1.pid")" 2>/dev/null || true
  record "load-$1" "$(head -1 "$WORK/load-$1.at")" 0 \
    "$(python3 "$KIND_DIR/load.py" "$WORK" "$1" "$(kubectl exec deploy/mongo -- getconf CLK_TCK)")"
}

# mongo_log NAME START_MS: MongoDB's log since START_MS into cluster/mongo-NAME.log, its slow queries among it, taken as
# the step ends: kubelet rotates the container's log, so one taken at the end of the run keeps only its last minutes.
mongo_log() {
  mkdir -p "$WORK/cluster"
  kubectl logs deploy/mongo --since-time="$(date -u -d "@$(($2 / 1000))" +%Y-%m-%dT%H:%M:%SZ)" \
    >"$WORK/cluster/mongo-$1.log" 2>&1 || true
}

# logged NAME EXPECTED CMD...: step NAME, then MongoDB's log of it (mongo_log); logged NAME compared CMD... runs it as
# compared does, which passes the differences EXPLAINED_KINDS explains.
logged() {
  local name="$1" expected="$2" start
  shift 2
  start="$(now_ms)"
  if [ "$expected" = compared ]; then
    compared "$name" "$@"
  else
    step "$name" "$expected" "$@"
  fi
  mongo_log "$name" "$start"
}

# cutover_bench: the write stop of an online cut-over by two migrate-data images, the baseline
# (wow-migrate-data:baseline, A) and the candidate (wow-migrate-data:local, B), alternating A B B A (D392 ③), each
# into a fresh database with 9.x serving:
# - the import;
# - the dual run: the tail follows 9.x while the driver writes to it for DUAL_RUN_SECONDS; B first walks every
#   instance with compare and snapshot, as its run book has them in the dual run, and A, as main's has them in the
#   write stop, does not;
# - the tail's last pass, once the writes have ended (tail --once, the Deployment deleted);
# - the write stop, each step timed: import --final, compare, snapshot.
# Each step's MongoDB log is kept (mongo_log). Each database is dropped once its run is timed. 9.x's store grows by
# each run's writes: each run imports it as it then is.
cutover_bench() {
  local run=0 arm name
  for arm in A B B A; do
    run=$((run + 1))
    name="cut-$run-$arm"
    TARGET_DB="cut_${run}_$(tr '[:upper:]' '[:lower:]' <<<"$arm")"
    createdb "$TARGET_DB"
    write_config "$TARGET_DB"
    export MIGRATE_IMAGE=wow-migrate-data:local
    [ "$arm" = A ] && export MIGRATE_IMAGE=wow-migrate-data:baseline
    logged "$name-import" 0 migrate import
    tail_start
    if [ "$arm" = B ]; then
      logged "$name-dual-compare" compared migrate compare
      logged "$name-dual-snapshot" 0 migrate snapshot
    fi
    views_traffic_start 9x "$NINEX_URL" "$DUAL_RUN_SECONDS" "$name-views-traffic"
    step "$name-traffic" 0 driver traffic --version=9x --base="$NINEX_URL" --book="$WORK/book.tsv" \
      --seconds="$DUAL_RUN_SECONDS" --touched="$WORK/$name-touched.tsv"
    views_traffic_join "$name-views-traffic"
    tail_stop
    logged "$name-tail" 0 migrate tail --once
    logged "$name-final" 0 migrate import --final
    logged "$name-compare" compared migrate compare
    logged "$name-snapshot" 0 migrate snapshot
    unset MIGRATE_IMAGE
    kubectl exec deploy/postgres -- dropdb -U postgres "$TARGET_DB"
  done
}

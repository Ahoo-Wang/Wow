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
# wow-view-store-server:local, loaded into the cluster.

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

# config_json DB MONGO POSTGRES: migrate-data's configuration for the V10 log in database DB, the stores at MONGO and
# POSTGRES (host:port), as the runner arm's (rehearse.sh write_config) but for the cluster: the checkpoints in the
# target, the password from the Secret, the classes where the init containers copy them, and no manifests: the tool
# reads them from those classes.
config_json() {
  cat <<EOF
{
  "map": "migration-map.json",
  "checkpoint": "target:rehearsal",
  "source": { "uri": "mongodb://$2", "database": "wow_example_db", "createCommitTimeIndex": true },
  "target": {
    "type": "postgres",
    "jdbcUrl": "jdbc:postgresql://$3/$1",
    "username": "postgres",
    "password": "env:WOW_PG_PASSWORD"
  },
  "partitions": { "example": 64, "view-store": 64 },
  "classpath": ["/service/migration-rehearsal", "/service/view-store-server"],
  "compensation": { "database": "compensation_db", "service": "example-service" },
  "lookbackMillis": 20000,
  "pollIntervalMillis": 500,
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
import json, sys
templates, kind, name, args = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
item = next(item for item in json.load(open(templates))["items"] if item["kind"] == kind)
if kind == "Job":
    item["metadata"]["name"] = name
item["spec"]["template"]["spec"]["containers"][0]["args"] = args
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
  render Job "$name" "$@" | kubectl create -f - >/dev/null
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

# The tail, a Deployment of one replica (deploy/migrate-data/tail), its log followed into $WORK/tail.log.
tail_start() {
  render Deployment migrate-data-tail tail --config /config/migrate-data-example.json | kubectl apply -f - >/dev/null
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

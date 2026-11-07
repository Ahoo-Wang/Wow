# migrate-data on Kubernetes

Plain manifests that run `migrate-data`, the tool that moves a 9.x service's data into Wow 10, inside the cluster, next to the stores it reads and writes. Each command is a Job of the `wow-migrate-data` image, and the tail is a one-replica Deployment. [Migrate the data](https://wow.ahoo.me/migrate/data) is the procedure; [the reference](https://wow.ahoo.me/reference/migrate-data) lists every command and setting.

| File | What it holds |
| --- | --- |
| `kustomization.yaml` | The shared part: `kubectl apply -k deploy/migrate-data` |
| `migrate-data.json` | The configuration, an example: in-cluster store addresses, the checkpoint kept in the target, the class path the init container fills |
| `migration-map.json` | The migration map, an example: replace it with your service's map |
| `secret.yaml` | The secrets the configuration reads as `env:NAME`, example values |
| `networkpolicy.yaml` | No ingress, and egress only to DNS and the stores |
| `job/` | The Job every command runs as: an init container that copies the service's `lib` from its own image, then the tool |
| `check/`, `import/`, `import-final/`, `compare/`, `snapshot/` | One Job per command: `kubectl create -k deploy/migrate-data/<command>` |
| `tail/` | The tail, a Deployment of one replica that follows 9.x until the cut-over |

## Before you apply

1. **The image.** Use the released one, `ahoowang/wow-migrate-data:<version>`, of the Wow version your service runs on, or build it from the repository root:

   ```bash
   ./gradlew :migrate-data:installDist
   docker build -t wow-migrate-data:local migration/migrate-data
   ```

   For a local cluster, load the image into it (`kind load docker-image wow-migrate-data:local`). Otherwise set `image` in `job/job.yaml` and `tail/deployment.yaml`.

2. **The service's classes.** `compare`, `snapshot` and `check --decode` rebuild states with the service's own V10 models, and the tool reads the service's Manifest from the fragments its modules carry. Neither is in the tool's image. The init container `service` copies the service's distribution `lib` from the image the service's nodes run into a shared volume, which `classpath` names (`/service/lib`). Set its `image` and the path of the distribution in that image (`/opt/<service>/lib` for an image built as the platform images are). The image needs `cp`. Leave `manifests` out of the configuration: the tool assembles the Manifest from the fragments on `classpath` as the node does at startup, and fails, naming the context, when one the map imports into is not there.

3. **The configuration and the map.** Put your map in `migration-map.json`, and set the store addresses, the database names, the partitions and the compensation service in `migrate-data.json`. Both are mounted read-only at `/config`.

4. **The secrets.** `secret.yaml` holds example values. Create the secret from your own store instead, with the names the configuration reads (`env:NINEX_MONGO_URI`, `env:TARGET_PG_PASSWORD`).

5. **The network.** Set the selectors in `networkpolicy.yaml` to your stores' pods, or use an `ipBlock` for a store outside the cluster. The pods reach DNS, the 9.x MongoDB (the source, and the compensation database's server), and the V10 target. They serve no port. The stores' own policies must admit them, by `app.kubernetes.io/name: migrate-data`, as [`deploy/mongodb`](../mongodb/README.md) does.

## The checkpoint

A Pod may restart on another node, so the configuration keeps the checkpoints in the V10 target, beside the log: `"checkpoint": "target:order-service"`. The default, a file next to the configuration, would be lost with the Pod, and `/config` is read-only.

Only one run of each kind writes a checkpoint at a time:

- one `import`, `import --final`, `tail` or `export`;
- one `compare`;
- one `snapshot`.

Runs of different kinds share it: a `compare` Job runs beside the tail. Each run holds a lease in the target's lease store, renewed every 10 seconds. A Pod that was replaced mid-run left a lease that expires within 30 seconds, and the next run waits for it. A run that finds the lease renewed by a live run fails, naming it:

```text
FAILED The checkpoint target:order-service is in use: tail on migrate-data-tail-5c7d9 (pid 1, run 019a…) holds its import lease, renewed while it runs (now until 2026-11-03T10:15:30Z). One import, import --final, tail or export runs on a checkpoint at a time: stop that run, or wait for it to end.
```

## Run the commands

```bash
kubectl apply -k deploy/migrate-data
kubectl create -k deploy/migrate-data/check
kubectl logs -f job/migrate-data-check
```

`kubectl logs -f` follows the Job until the command ends. Its exit code is the Job's outcome:

- 0: the Job completes.
- 1 (failed), 2 (usage error) or 3 (differences): the Job fails at once, and running it again changes nothing.
- A Pod lost to its node or to its memory limit: the Job runs a new one, which resumes from the checkpoint.

To run a command again, delete its Job first:

```bash
kubectl delete -k deploy/migrate-data/check
kubectl create -k deploy/migrate-data/check
```

The offline path runs `import`, `compare` and `snapshot` one after the other, once 9.x has stopped writing.

The online path runs `import` while 9.x serves, then the tail until the cut-over, then the final pass:

```bash
kubectl create -k deploy/migrate-data/import
kubectl logs -f job/migrate-data-import
kubectl apply -k deploy/migrate-data/tail
# ... the dual run; at the cut-over, once 9.x has stopped writing:
kubectl delete -k deploy/migrate-data/tail
kubectl create -k deploy/migrate-data/import-final
kubectl logs -f job/migrate-data-import-final
```

Deleting the tail stops it after its current pass: the Pod receives `SIGTERM` and has two minutes to end it. Another command, such as `export` for a rollback, is a directory like `check/`, with its arguments in the patch.

## Choices

- **Memory.** The image sets `JAVA_OPTS=-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`: the heap is three quarters of the container's limit, 2 GiB here, and an `OutOfMemoryError` ends the process, so the Job runs it again from its checkpoint instead of leaving it hung. An import holds a page of instances with their streams, at most ten streams per instance (`pageSize`), so give a larger page more. A `JAVA_OPTS` of your own replaces the image's.
- **Time zone.** `compare` rebuilds states in the time zone 9.x ran in when domain code turns dates into instants with the system's zone: set `TZ`.
- **Retries.** A Job retries only what a new Pod can fix. The tool's own verdicts fail it at once (`podFailurePolicy`), and an evicted Pod is not counted against its retries.

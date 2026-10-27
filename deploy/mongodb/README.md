# MongoDB on Kubernetes, a starting point

A replica set of three data-bearing members, `rs0`, for the eShop's services and the platform's: the default backend of the manifests in `deploy/`. Each service keeps its data in a database of its own (`wow.mongo.database`, set by its `mongo` profile) and connects with:

```text
mongodb://mongodb-0.mongodb:27017,mongodb-1.mongodb:27017,mongodb-2.mongodb:27017/?replicaSet=rs0&w=majority
```

`w=majority` states what Wow asks for on every append anyway: a write a majority acknowledged, which a failover never rolls back. [Operate › MongoDB](https://wow.ahoo.me/operate/mongodb#recommended-topology) explains why three members and no arbiter.

| File | What it holds |
| --- | --- |
| `kustomization.yaml` | The set: `kubectl apply -k deploy/mongodb` |
| `statefulset.yaml` | The three members, one per node where it can, each with a 10 GiB volume |
| `service.yaml` | `mongodb`, the headless service that names the members `mongodb-<n>.mongodb` |
| `init-job.yaml` | Initiates the replica set once its members answer; harmless when it already is |
| `pdb.yaml` | One member at a time in a voluntary disruption |
| `networkpolicy.yaml` | Only the members, the initiation, and the pods labelled `app.kubernetes.io/part-of: eshop` or `wow-platform` reach the members |

```bash
kubectl apply -k deploy/mongodb
kubectl rollout status statefulset/mongodb
kubectl wait --for=condition=complete job/mongodb-init --timeout=300s
```

## Before it holds data you keep

These manifests run MongoDB without authentication, TLS or backups. Turn on authentication (a keyfile for the members, users for the services, the credentials in each service's `SPRING_MONGODB_URI` secret), TLS where your network asks for it, and back the volumes up. Or run MongoDB as your platform provides it, through an operator or a hosted service, and point the services' URIs there.

## A sharded cluster

For a cluster that outgrows one replica set, [Operate › MongoDB](https://wow.ahoo.me/operate/mongodb#sharded-clusters) describes the shard keys Wow's collections take. These manifests ship no sharded variant: point the services' URIs at the cluster's `mongos` routers instead.

# The eShop on Kubernetes

The five eShop services together, each a release of Wow's chart ([`deploy/helm/wow-service`](../helm/wow-service/README.md)) with the values its build generates and its own `values.yaml`, on MongoDB, or with `values-postgres.yaml` after it on PostgreSQL. Each service's own directory describes it; [eShop ordering](../eshop-ordering/README.md) explains the choices they share.

1. Build the five images and their values, as each service's README says (`./gradlew :eshop-<service>-server:installDist :eshop-<service>-server:generateWowHelmValues`), and provide Kafka at `kafka:9092` with the topics each service owns.
2. MongoDB: `kubectl apply -k deploy/mongodb`, a replica set to start from ([its README](../mongodb/README.md)), and each service's `secret.yaml`. Or PostgreSQL at `postgres:5432`, with a database per service (`eshop_catalog`, `eshop_basket`, `eshop_ordering`, `eshop_inventory`, `eshop_payment`), each service's `postgres-secret.yaml`, and the server settings of [Operate › PostgreSQL](https://wow.ahoo.me/operate/postgresql#server-settings).
3. Put the CoSec gateway in front, its pods labelled `app.kubernetes.io/component: gateway`, routing each context's prefix (`/eshop-ordering/...`) to its service (`eshop-ordering-service`) and forwarding the identity it verified (`CoSec-Identity`; a route to a Service's URL takes it with the route metadata `cosec-identity: true`). The services' `cosec` profile reads their callers from that header and believes it, so nothing but the gateway may reach them: each service's NetworkPolicy admits the gateway's pods alone. The gateway's own setup, its documents in a Redis with `maxmemory-policy noeviction` among them, is CoSec's ([Deploying the gateway server](https://cosec.ahoo.me/operations/deployment)); `.github/ga-e2e/gateway/` is the one Wow's end-to-end run deploys.
4. Install the shop, a release per service, and wait for each (on PostgreSQL, add `-f "deploy/eshop-$context/values-postgres.yaml"`):

   ```bash
   for context in catalog inventory payment ordering basket; do
     helm upgrade --install "eshop-$context" deploy/helm/wow-service \
       -f "examples/eshop/$context/$context-server/build/wow/helm/values.yaml" \
       -f "deploy/eshop-$context/values.yaml" --wait
   done
   ```

The services find each other's internal ports by DNS: the basket's checkout reaches the ordering's nodes through the SRV records of `eshop-ordering-service-cluster`, with no URL to set.

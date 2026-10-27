# The eShop on Kubernetes

The five eShop services together: `kubectl apply -k deploy/eshop`, on MongoDB, or `kubectl apply -k deploy/eshop-postgres` on PostgreSQL. Each service's own directory describes it; [eShop ordering](../eshop-ordering/README.md) explains the choices they share.

1. Build the five images, as each service's README says, and provide Kafka at `kafka:9092` with the topics each service owns.
2. MongoDB: `kubectl apply -k deploy/mongodb`, a replica set to start from ([its README](../mongodb/README.md)). Or PostgreSQL at `postgres:5432`, with a database per service (`eshop_catalog`, `eshop_basket`, `eshop_ordering`, `eshop_inventory`, `eshop_payment`) and the server settings of [Operate › PostgreSQL](https://wow.ahoo.me/operate/postgresql#server-settings).
3. Put the CoSec gateway in front, its pods labelled `app.kubernetes.io/component: gateway`, routing each context's prefix (`/eshop-ordering/...`) to its service (`eshop-ordering-service`).
4. Apply the shop and wait for it:

   ```bash
   kubectl apply -k deploy/eshop
   kubectl rollout status deployment/eshop-ordering-service
   ```

The services find each other's internal ports by DNS (decision D305): the basket's checkout reaches the ordering's nodes through the SRV records of `eshop-ordering-service-cluster`, with no URL to set.

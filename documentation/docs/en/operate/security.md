---
title: Secure a Deployment
description: How to authenticate callers, authorize commands, close the internal port to everyone but the system's own services, and keep personal data out of a log that is never changed.
---

# Secure a Deployment

A Wow service has two doors: the public port, where callers send commands and queries, and the internal port, where the system's own nodes and services talk to each other. This page explains how to secure each, what the defaults are, and what the log means for personal data. [Identity, tenancy and access](../concepts/identity) explains the model behind it.

## Checklist

- A caller resolver authenticates every request to the public port; CoSec provides one.
- Commands are authorized per action, not only per route, where routes alone are not enough.
- The internal port is unreachable from outside the cluster, and every service has a cluster token of at least 32 characters.
- Each service lists the services it trusts on its internal port, and the commands each may call.
- Tokens and database passwords are secrets, managed like each other.
- Events hold no personal data you may have to erase.

## Authenticate callers

The host turns each request into a caller with its `CallerResolver`. Without one, every request is served as the anonymous caller of the default tenant, and the host logs a warning at startup. Run that way only for trying things out.

With CoSec on the classpath, its resolver is the host's. It reads the caller from CoSec's security context:

- **Tenant and subject** come from the principal. A request without an authenticated principal is answered 401.
- **Owner.** A principal is an owner-level caller, acting on its own aggregates, unless it has one of the roles or policies of `wow.cosec.tenant-level`: then it is a tenant-level caller, acting on every aggregate of its tenant, such as a member of staff who ships every buyer's orders.
- **Space.** A principal's spaced roles (`role@space`) are the spaces it belongs to. The `Wow-Space` header picks one; without it, a principal acts in its only space, or in none.
- **Acting elsewhere.** Only a principal with a role or policy of `wow.cosec.cross-scope` may name another tenant or scope in `Wow-Scope`.
- **Services.** Only a principal with a role or policy of `wow.cosec.service` is a service identity, which may make synchronous calls.
- **Application.** The request's `CoSec-App-Id` becomes the caller's application, and the commit's `app_id` fact, only when CoSec authorized the request through that application's role permissions. A header the client sets is not trusted on its own. Behind a CoSec gateway in inject mode, `wow.cosec.trust-injected-app` trusts the application the gateway forwards; it is off by default, and only safe when nothing but the gateway reaches the service.

## Authorize commands

Authorization happens at admission, before the command runs:

- **Per route.** CoSec's web filter authorizes the public routes by path and method.
- **Per action.** With `wow.cosec.authorizer.enabled: true`, CoSec also authorizes every command as its caller, whichever way it arrived. It is off by default: without it, and without an authorizer of your own, a command that passed its route is allowed.
- **Per scope.** Whatever the authorizer says, the kernel refuses a command on an aggregate outside the caller's scope, and queries only return what the caller may see.

Commands a saga sends, compensations and scheduled commands inherit the scope and operator of the root command, which was authorized when it was admitted.

The operator's routes over parked aggregates, `/wow/ops/parked`, are off by default (`wow.ops.enabled`). Turned on, their actions are authorized as the system commands `wow.ledger.*`, and the host warns when no command authorizer would check them.

## Close the internal port

The internal port carries forwarded commands, wait signals, synchronous calls and reads between services. Whoever can send a signal can fake the result of any command, so:

1. **Keep it inside the cluster.** No public load balancer, ingress or port mapping points at it, and a network policy lets only the system's own pods reach it.
2. **Bind it to the private interface** with `wow.internal.host`.
3. **Give every service a cluster token** in `wow.internal.token`: a random secret of at least 32 characters, the same on all the service's nodes. Forwards and signals between them must carry it. A node without one starts only in a development profile, or with its internal port on the loopback interface alone.
4. **Name the services you trust.** Another service reaches the internal port with its service identity: a static token listed under `wow.http.services`, or a CoSec principal with a role of `wow.cosec.service`. A service identity can send signals and make calls and reads; it cannot forward commands, and a cluster token cannot make calls.
5. **List the commands each service may call.** A synchronous call may invoke the target's domain commands. Lifecycle and system commands, such as delete or rebuild a snapshot, must be listed for the calling service, and the list can narrow the domain commands too. With CoSec, every call is authorized as its service principal by CoSec's policies, whatever `wow.cosec.authorizer.enabled` says.

The eShop's ordering service puts it together. Its basket service calls one command synchronously; its inventory and payment services signal waits that crossed into them:

<!-- sample: example/eshop/ordering/ordering-server/src/main/resources/application.yaml#internal-port -->

```yaml
wow:
  service: eshop-ordering-service
  # One node, its data in memory, for trying the service out. The `postgres` profile serves it from PostgreSQL; the
  # `kafka` profile connects it to the other contexts, which the node needs to start: its sagas send the inventory's
  # commands.
  backend: memory
  internal:
    port: 9093
    # On this machine only, for trying the shop out: the basket service calls the order gateway here.
    host: 127.0.0.1
    # The cluster token this service's nodes share (decision D226): it alone opens their forwards and the signals of
    # their waits. The value is for trying the shop out on one machine; a deployment sets WOW_INTERNAL_TOKEN, a secret
    # of at least 32 characters, the same on every node of the service.
    token: ${WOW_INTERNAL_TOKEN:eshop-ordering-local-cluster-token-not-a-secret}
  # This service's identity where it signals the waits that crossed into it from another service (decision D226).
  calls:
    token: ${ESHOP_ORDERING_SERVICE_TOKEN:eshop-ordering-local-service-token-not-a-secret}
  http:
    # The basket service's checkout calls this command gateway synchronously (decision D193), on the internal port
    # alone: a request with its service token is that service's identity, which alone may name the buyer the order is
    # for. The inventory and the payment services signal, with theirs, the waits that started here and crossed into
    # them (decision D226). The tokens are for trying the shop out on one machine; a deployment sets
    # ESHOP_BASKET_SERVICE_TOKEN, ESHOP_INVENTORY_SERVICE_TOKEN and ESHOP_PAYMENT_SERVICE_TOKEN, here and in those
    # services.
    services:
      eshop-basket-service:
        tokens:
          - ${ESHOP_BASKET_SERVICE_TOKEN:eshop-basket-local-service-token-not-a-secret}
        # The commands the basket may call (decision D226): its checkout submits the order, and nothing else. A system
        # command (wow.*: delete, restore, transfers, tags) is never called unless listed here.
        commands:
          - eshop-ordering.order.SubmitOrder
      eshop-inventory-service:
        tokens:
          - ${ESHOP_INVENTORY_SERVICE_TOKEN:eshop-inventory-local-service-token-not-a-secret}
      eshop-payment-service:
        tokens:
          - ${ESHOP_PAYMENT_SERVICE_TOKEN:eshop-payment-local-service-token-not-a-secret}
```

Its Kubernetes network policy lets anyone the cluster admits reach the HTTP port, and only the shop's own pods reach the internal port:

<!-- sample: deploy/v10/eshop-ordering/networkpolicy.yaml -->

```yaml
# Decision D173: only the eShop's own pods reach the internal port (this service's nodes, and the services whose waits
# cross into it); anyone the cluster lets in reaches the HTTP port. Enforced where the cluster's network plugin
# enforces NetworkPolicy.
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: eshop-ordering
  labels:
    app.kubernetes.io/name: eshop-ordering
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: eshop-ordering
  policyTypes:
    - Ingress
  ingress:
    - ports:
        - port: http
          protocol: TCP
    - from:
        - podSelector:
            matchLabels:
              app.kubernetes.io/part-of: eshop
      ports:
        - port: internal
          protocol: TCP
```

A network plugin that does not enforce network policies ignores this one; the tokens still hold.

**Rotating the cluster token.** `wow.internal.token` takes several tokens, comma-separated. A node sends the first and accepts any. To rotate, give `old,new` and roll the nodes, then `new,old` and roll, then `new` alone.

## Mistyped settings

A mistyped security setting would silently leave its default in place, and some defaults allow everything. So the host refuses to start when an unknown key appears under `wow.cosec.*`, `wow.http.services.*`, `wow.calls.*` or `wow.internal.*`; under `wow.http.services` and `wow.calls.contexts`, each entry's keys are checked too. Other unknown `wow.*` keys are logged as warnings.

## Personal data in events

Events are facts. The log keeps them for good, and Wow offers neither erasing events nor crypto-shredding. Plan for that in the model:

- Keep personal data, such as names, addresses, phone numbers and free text about a person, out of events where you can. Keep it in a read model you can delete from, such as a projection's table or another service, and put an opaque id in the event.
- When decide needs personal data, let it read the data through a domain service instead of carrying it in an event.
- The operator of a commit is recorded as an opaque id. Request facts are recorded with every commit: the authenticated application and, for requests on the public port, the client's remote address and user agent. An IP address and a user agent are personal data, and once written they cannot be erased. `wow.http.client-facts` (default `true`) switches the address and user agent off: with `false`, the public port records neither. Do not add further personal data to `wow.http.request-facts`.
- Snapshots, the warehouse and search indexes are derived from the log: deleting a row there does not delete the fact.

## Tenants

All the services of a system share one tenant space: a tenant id means the same customer in every service, and crosses services as it is. Resolve the caller's tenant the same way in every service. An aggregate type can be given a fixed tenant, `wow.aggregates.[<context>.<aggregate>].tenant`, which puts all its commands and reads in that tenant whoever the caller is.

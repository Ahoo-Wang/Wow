---
title: Secure a Deployment
description: How to authenticate callers, authorize commands, close the internal port, Kafka and the stores to everyone but the system's own services, and keep personal data out of a log that is never changed.
---

# Secure a Deployment

A Wow service has two doors: the public port, where callers send commands and queries, and the internal port, where the system's own nodes and services talk to each other. This page explains how to secure each, what the defaults are, and what the log means for personal data. [Identity, tenancy and access](../concepts/identity) explains the model behind it.

## Checklist

- The public port is reachable from the gateway alone.
- The internal port, Kafka and the stores are never reachable from outside the platform: network policies, as in `deploy/`, let only the platform's own pods reach them.
- The gateway writes the forwarded headers itself and removes any `CoSec-Identity` a client sends; it never passes a client's on.
- A caller resolver authenticates every request to the public port; CoSec provides one.
- CoSec's documents are published, to a Redis that runs with `maxmemory-policy noeviction`, before the first request.
- Commands are authorized per action, not only per route, where routes alone are not enough.
- Each service lists the system commands other services may call on its internal port, and, where it helps catch a mistake, the domain commands and models.
- Only a service's own nodes, the services that send it commands, and ops write its command topic.
- Database and broker passwords are secrets.
- Events hold no personal data you may have to erase.

## Authenticate callers

The host turns each request into a caller with its `CallerResolver`. Without one, a node in development (a profile of `wow.dev-profiles`, or an internal port on the loopback interface alone) serves every request as the anonymous caller of the default tenant and logs a warning; any other node refuses to start. A service that is anonymous on purpose says so with a `CallerResolver` bean returning `CallerResolver.ANONYMOUS`.

::: warning wow-cosec is experimental in 10.0
`wow-cosec` follows CoSec 8.0.0, which is still a pre-release, so it may change within 10.x. Its types (`CoSecCallerResolver`, `CoSecCallerOptions`, `CoSecCommandAuthorizer`, `CoSecServiceRouteAuthorizer`, `CoSecActions`) and the host's `CoSecAutoConfiguration` and `CoSecRouteMapAutoConfiguration` are `@ExperimentalWowApi` ([API Levels](../reference/api-levels)), and the `wow.cosec.*` keys may change with them. A service that turns CoSec on with its dependencies and keys alone needs nothing more; Kotlin code that names one of these types opts in with `@OptIn(ExperimentalWowApi::class)`. Once CoSec 8 is GA, a 10.x minor release drops the marker. A service without CoSec is not affected: the rest of the API keeps its level.
:::

With `wow-cosec` and CoSec's starter and WebFlux module (`me.ahoo.cosec:cosec-spring-boot`, `me.ahoo.cosec:cosec-webflux`) on the classpath, CoSec's resolver is the host's, unless the application defines a `CallerResolver` of its own. CoSec's pipeline authenticates each request and resolves its scope, the app and space the request is decided in; the resolver turns the security context CoSec built into the caller:

- **Tenant and subject** come from the principal; CoSec's default tenant, `(0)`, is `wow.tenant.default`. A request without an authenticated principal, or whose security context CoSec's pipeline did not build, is answered 401.
- **Owner.** A principal is an owner-level caller, acting on its own aggregates, unless it has one of the roles or policies of `wow.cosec.tenant-level`: then it is a tenant-level caller, acting on every aggregate of its tenant, such as a member of staff who ships every buyer's orders.
- **Space** is the space of CoSec's scope. The client names it in `CoSec-Space-Id`, and CoSec accepts it only when the principal has a role in that space; without it, the request is in CoSec's default space, which is no Wow space. A CoSec host does not read `Wow-Space`.
- **Application.** The caller's application (`Caller.app`), recorded as the commit's request fact `app_id`, is the app of CoSec's scope: the host's own (`cosec.authorization.scope.app-id`), else the `CoSec-App-Id` the request declared and CoSec accepted, else, behind a CoSec gateway, the app the gateway forwarded. A scope without an app gives the caller none.
- **Roles and attributes.** The caller's `roles` attribute lists the principal's roles in the scope's space, `policies` its policies, and its principal attributes whose key starts with `abac.` are its ABAC attributes. Behind a CoSec gateway a principal has no policies, so only the roles of `wow.cosec.tenant-level` and `wow.cosec.cross-scope` match there, and the ABAC attributes come from the claims the gateway forwards (`cosec.gateway.identity.claims`).
- **Acting elsewhere.** Only a principal with a role or policy of `wow.cosec.cross-scope` may name another tenant or scope in `Wow-Scope`.
- **Services.** A CoSec principal is never a service identity. A service names itself on the internal port alone ([Close the internal port](#close-the-internal-port)).

CoSec's guide describes how it authenticates a request and how it checks the app and space a client declares: [Authentication](https://cosec.ahoo.me/integration/authentication), [App and space](https://cosec.ahoo.me/integration/web#app-and-space).

## Record the client address

Each command records the client's `User-Agent` and address (`user_agent`, `remote_ip`), each cut to 512 characters. A gateway sits in front of the public port, so the host takes the address from its `X-Forwarded-For`: it sets Spring Boot's `server.forward-headers-strategy` to `framework` unless the application sets it, and logs the strategy in effect at startup. The gateway must overwrite the header a client sends. Set the strategy to `none` to record the connection's address instead.

## Authorize commands

Authorization happens at admission, before the command runs:

- **Per route.** Where CoSec decides a request, its pipeline authorizes it, by an action on a resource, before the route runs.
- **Per action.** With `wow.cosec.authorizer.enabled: true`, CoSec also authorizes every command and service route call of a request as its caller ([Where CoSec decides](#where-cosec-decides)). A `CommandAuthorizer` of your own does the same.
- **Per scope.** Whatever the authorizer says, the kernel refuses a command on an aggregate outside the caller's scope, and queries only return what the caller may see.

The operator's routes over parked aggregates, `/wow/ops/parked`, are off by default (`wow.http.ops.enabled`). Turned on, their actions and reads are authorized as the system commands `wow.ledger.*`. Without a command authorizer every one of them is refused (403 `wow.auth.forbidden`), never allowed by default, and the host warns at startup.

The system commands of Wow's built-in aggregates follow the same rule on the public port: a scheduled job's commands (`POST /<context>/wow.schedule/{id}/enable`, `reschedule` and `disable`) and the snapshot rebuild of an aggregate type (`POST /<context>/<aggregate>/wow.snapshot.rebuild`). Without a command authorizer the public port refuses them to every caller (403 `wow.auth.forbidden`), the anonymous one included, and the host says so at startup. With one, the authorizer decides: under CoSec, a policy grants their actions ([below](#action-names)) to the operators. A service in gateway mode, which has no CoSec authorizer, needs an authorizer of its own for them. Only the public port refuses them: the jobs' own commands, the sagas' and the commands another service, such as ops, calls on the internal port are sent as before. An aggregate's lifecycle commands (`wow.lifecycle.*`) are on the aggregate itself, authorized and scope-checked as its domain commands are.

### Action names {#action-names}

A command and a service route have one CoSec name, whether CoSec decides the HTTP request or the authorizer decides the command in the process, so one policy guards both:

| What | Action | Resource |
| --- | --- | --- |
| A command | `<context>:<command>`, its name as the Manifest lists it: `eshop-ordering:CreateOrder`, `shop:wow.lifecycle.DeleteAggregate` | `<aggregate>/<id>`: `order/o-1` |
| A command route that names no aggregate id, such as a creation | `<context>:<command>` | `<aggregate>`: `order` |
| The snapshot rebuild of an aggregate type | `<context>:wow.snapshot.Rebuild` | `snapshot/<aggregate>` |
| A scheduled job's command | `<context>:wow.schedule.Enable` and the other job commands | `schedule/<job>` |
| A service route | `<context>:<route>` | `route/<route>` |
| A query or state route | `http:<METHOD>` | its path |

A route that names no aggregate id leaves the id to the server, so its HTTP request is on the aggregate type alone while the command, in the process, is on the aggregate it addresses: a policy for such a command names the aggregate type both with and without an id. A policy on `http:` actions never covers the command and service routes: they are in their context's namespace.

CoSec's documents, the platform's, each app's and each tenant's, say who may do what. Write them with CoSec's guide rather than from this page: [Policy language](https://cosec.ahoo.me/policies/language), [Role permissions](https://cosec.ahoo.me/policies/role-permissions), [Testing policies](https://cosec.ahoo.me/policies/testing).

### Where CoSec decides {#where-cosec-decides}

- **In a service that decides for itself** (`cosec.pipeline.mode` `enforce`, CoSec's default), CoSec's pipeline reads the route map the host builds from the service's Manifest: its command and service routes, under the names above, in place of a `cosec-routes.json`. An application that declares a `RouteMap` bean of its own keeps it. Such a service holds CoSec's signing secret and reads its documents from CoSec's Redis ([CoSec's quick start](https://cosec.ahoo.me/guide/quick-start)).
- **Behind a CoSec gateway** (`wow.cosec.mode: gateway`), the gateway decides. It loads no route map, so its policies name `http:<METHOD>` actions on the request's path, and the service decides nothing by policy ([Run services in gateway mode](#run-services-in-gateway-mode)).

With `wow.cosec.authorizer.enabled: true`, `CoSecCommandAuthorizer` and `CoSecServiceRouteAuthorizer` authorize each command and service route call in the process, whichever way it reached the command gateway, by its action above, for the principal and in the scope of the request it came from. They are wired only where CoSec decides for itself, which needs CoSec's `AuthorizationChecker`: a service in gateway mode has none, and the switch does nothing there. The switch is off by default: without it, and without an authorizer of your own, a command that passed its route is allowed, except the built-in aggregates' system commands above. A `CommandAuthorizer` of your own is asked beside CoSec's, and a command needs both to allow it ([the composition rule](../guide/spring-boot#beans-the-application-gives)); an authorizer of your own roles that should decide only without CoSec takes `ObjectProvider<CoSecCommandAuthorizer>` and has no objection when there is one, as the platform services' do.

A command that no request carries gets no objection from the CoSec authorizers: a command a saga, a timer or a compensation sends, a scheduled job's command, and a call another service makes on the internal port. CoSec has no request of theirs to decide. Such a command carries the scope and operator of the root command, which admission authorized when the root request arrived; the node's other authorizers still apply to it, and a service's calls on the internal port are what its caller list allows ([Close the internal port](#close-the-internal-port)).

## Put the gateway in front

Wow trusts the identity the gateway forwards, so the gateway is a boundary of its own. Two requirements hold for every deployment:

1. **The public port is reachable from the gateway alone.** A client that reached the public port around the gateway could send a `CoSec-Identity` of its own, and be anyone. The deploy manifests' network policies let only the gateway's pods reach it.
2. **The gateway overwrites the headers the service trusts.** It sets the forwarded headers, `X-Forwarded-For` and `Forwarded`, from the connection it accepted, instead of passing a client's on or appending to them, and it removes any `CoSec-Identity` the client sent, forwarding only the identity it verified; the CoSec gateway does the latter on every route, whatever its configuration. Otherwise a client chooses its own address or identity.

The host cannot see the network, so it does not check either at startup.

A command sent again is the same command only under the same request id. A client that resends a command after losing its answer sends it with the same `Wow-Request-Id`, and the service answers it as a duplicate instead of running it twice. With CoSec, a command without `Wow-Request-Id` takes the request's `CoSec-Request-Id`. The CoSec gateway sets it once on each request it forwards, and a retry of the request, such as Spring Cloud Gateway's after a connection reset, resends the same one, so the service answers the retry as a duplicate. `Wow-Request-Id` wins when a request carries both.

### Run services in gateway mode {#run-services-in-gateway-mode}

Behind a CoSec gateway, a service need not verify a token again, nor hold CoSec's documents. `wow.cosec.mode: gateway` sets CoSec's defaults for it: `cosec.enabled=true`, `cosec.pipeline.mode=gateway` and `cosec.jwt.enabled=false`. A `cosec.*` key the application sets still wins.

```yaml
wow:
  cosec:
    mode: gateway
```

The gateway authenticates and authorizes each request and forwards what it verified in `CoSec-Identity`: the principal, its tenant, and the app, space and roles of its scope. The service reads the caller from it. It holds no signing secret, no documents and no Redis of CoSec's, and has no `AuthorizationChecker`: what a caller may do beyond the gateway's decision is for the service's own authorizers to say. `wow.cosec.mode: disabled` turns CoSec off (`cosec.enabled=false`) for a service run without a gateway. The eShop's services and Wow's platform services, the ops service, the view store and the analytics catalog, run in gateway mode in their `cosec` profile.

**Only the gateway may reach a service in gateway mode.** It believes any `CoSec-Identity` it receives: anyone else who can send it a request is whoever they claim. Enforce it in the network, with a network policy that admits only the gateway's pods, as the deploy manifests do. A request without the header is anonymous, which the resolver answers 401; CoSec answers an unreadable header 400, and one older than `cosec.pipeline.gateway.max-age` (60 seconds) 401. CoSec's guide has the details: [Services behind the gateway](https://cosec.ahoo.me/integration/web#services-behind-the-gateway), [Trust boundaries](https://cosec.ahoo.me/guide/security-model#trust-boundaries).

### Configure the CoSec Gateway {#configure-the-cosec-gateway}

The CoSec Gateway (`cosec-gateway-server`, the image `ghcr.io/ahoo-wang/cosec-gateway`), built on Spring Cloud Gateway, is the gateway Wow's end-to-end tests run in front of the eShop and the platform services, with the settings below. They are the requirements above in that gateway's terms:

- **It is the edge.** `server.forward-headers-strategy: none`: it never takes a client's `Forwarded` or `X-Forwarded-For` as its own remote address, as Spring Boot's default on Kubernetes would.
- **It writes the client's address once.** From Spring Cloud Gateway 4.1, the X-Forwarded headers filter writes nothing unless `trusted-proxies` matches the peer, so `for-append: false` alone does nothing. `trusted-proxies: ".*"` with `for-append: false` writes the peer it accepted as the one `X-Forwarded-For`, replacing a client's, and `forwarded.enabled: false` sends no `Forwarded` header, which a service would prefer.
- **It retries a lost connection, never an answer.** The `Retry` filter resends a request once on an `IOException` or a `TimeoutException`, `POST` included: the retry carries the same `CoSec-Request-Id`, or the client's `Wow-Request-Id`, so the service answers it as a duplicate. `series: INFORMATIONAL` matches no final status, so no response is retried. Pooled connections idle at most 15 seconds, so one to a pod that has gone is rarely reused.
- **It decides with CoSec's documents in its Redis.** The gateway reads CoSec 8's documents (schema 2: the platform document, and one per app and per tenant) from a Redis that must run with `maxmemory-policy noeviction`: it checks the policy at startup and does not start otherwise. Publish the documents before traffic arrives, with `cosec-migrate publish --to-redis`: until a platform document exists, the gateway answers every routed request 503 and reports itself not ready. No Wow service connects to this Redis.

<!-- sample: .github/ga-e2e/gateway/gateway.yaml#settings -->

```yaml
server:
  port: 8080
  # The gateway is the edge: it never takes a client's Forwarded or X-Forwarded-For as its remote address. Spring
  # Boot defaults to the native strategy on Kubernetes, which would (run 7 recorded the claimed 203.0.113.9).
  forward-headers-strategy: none
spring:
  data:
    redis:
      host: redis
      port: 6379
  cloud:
    gateway:
      server:
        webflux:
          # The X-Forwarded headers filter writes X-Forwarded-* only for a peer trusted-proxies matches (Spring Cloud
          # Gateway 4.1+); unset, it writes none and the service sees the gateway's own address. Every peer matches:
          # the gateway is the edge, its peer is the client (server.forward-headers-strategy: none above), and
          # for-append: false writes that peer as the one X-Forwarded-For, whatever the client sent.
          trusted-proxies: ".*"
          x-forwarded:
            for-append: false
          # Only X-Forwarded-For carries the client's address on: a Forwarded header the services would prefer over
          # it is not sent.
          forwarded:
            enabled: false
          # Connections to a service's pods idle at most 15 s: a pod that died stays in the endpoints a little
          # while, and a pooled connection to it answers a reset; shorter than that, a stale one is rare.
          httpclient:
            pool:
              max-idle-time: 15s
          # Every route retries a request once when its connection fails (reset, refused, timed out), POST
          # included: Wow deduplicates a command by its request id, the client's Wow-Request-Id or else the
          # CoSec-Request-Id the gateway sets once per exchange, and a retry carries the same one
          # (the "a resent request keeps its request id" scenario, through the flaky upstream below). A response is
          # an answer and never retried (series INFORMATIONAL: no final status matches).
          default-filters:
            - name: Retry
              args:
                retries: 1
                methods: GET,POST,PUT,PATCH,DELETE
                series: INFORMATIONAL
                exceptions: java.io.IOException,java.util.concurrent.TimeoutException
```

Each route sends a context's path prefix to its service:

<!-- sample: .github/ga-e2e/gateway/gateway.yaml#routes -->

```yaml
# Each service by its context prefix. A route takes the CoSec-Identity of a permitted request only when
# it opts in (metadata cosec-identity: true) or targets lb://: these target a Kubernetes Service's URL.
routes:
  - id: eshop-catalog
    uri: http://eshop-catalog-service
    predicates: [Path=/eshop-catalog/**]
    metadata: { cosec-identity: true }
  - id: eshop-basket
    uri: http://eshop-basket-service
    predicates: [Path=/eshop-basket/**]
    metadata: { cosec-identity: true }
```

The gateway verifies each request's token with CoSec's signing secret (`cosec.jwt.secret`), decides it by the documents and forwards the identity. Its settings and the documents' format are CoSec's: [Deploying the gateway server](https://cosec.ahoo.me/operations/deployment), [The first documents](https://cosec.ahoo.me/operations/storage#the-first-documents), [Configuration](https://cosec.ahoo.me/operations/configuration). A coarse document at the gateway, such as one that lets any authenticated principal through, leaves what each caller may do to the services' own authorizers.

The gateway's pods carry `app.kubernetes.io/component: gateway`, the label the services' public network policy admits.

## Close the internal port

The internal port carries forwarded commands, wait signals, synchronous calls and reads between the platform's nodes and services. The inside of the platform is trusted: the internal port takes no token, and its boundary is the network. Whoever can send a signal can fake the result of any command, so:

1. **Keep it inside the platform.** No public load balancer, ingress or port mapping points at it, and a network policy lets only the platform's own pods reach it. The same holds for Kafka and the stores.
2. **Bind it to the private interface** with `wow.internal.host`.
3. **Every request names its service.** A node or service puts its service name, `wow.service` or else `spring.application.name`, in the `Wow-Service` header of each forward, signal, call and read. A service name is letters, digits, `.`, `_` and `-`, starting with a letter or a digit, at most 253 characters; a node whose service name is not such a name does not start. The header is a statement, not a credential: it catches a misconfiguration, such as a call a service's list does not allow, or a forward sent to another service's node.
4. **Each route takes the services it serves.** A forward, and the standby and resume routes (`GET /wow/node/standby`, `POST /wow/node/resume`), are taken only from a node of the same service: without the header the answer is 401 `wow.auth.unauthenticated`, from another service 403 `wow.auth.forbidden`. A wait signal is taken from any service that names itself, and 401 without the header. A synchronous call (`Wow-Call`) or a read is taken from any service that names itself, as that service's identity (subject `service:<name>`), and 403 for a request that names none. The public port never reads `Wow-Service`, and refuses `Wow-Call` with 403.
5. **List what each service may call, where it helps.** Under `wow.callers.<service>`, keyed by the name the caller declares, `commands` lists the commands that service may call and `reads` the models it may read. Both are optional: without `commands` a service may call every domain command, without `reads` read every model. System commands (`wow.*`, such as delete, restore or rebuild a snapshot) are never called unless listed. The lists catch a mistake; they are no boundary against a pod that lies about its name.

The eShop's ordering service puts it together. Its basket service calls one command synchronously, and nothing else; its inventory and payment services signal waits that crossed into them, which needs no entry:

<!-- sample: examples/eshop/ordering/ordering-server/src/main/resources/application.yaml#internal-port -->

```yaml
wow:
  # One node, its data in memory, for trying the service out. The `mongo` profile serves it from MongoDB, the `postgres`
  # profile from PostgreSQL; the `kafka` profile connects it to the other contexts; without it, the node on this machine
  # keeps its topics in its JVM (wow.transport), where the inventory does not see the commands its sagas send.
  internal:
    port: 9093
  # The basket service's checkout calls this command gateway synchronously, on the internal port
  # alone, naming itself (Wow-Service): the internal port is reachable by the shop's own pods alone,
  # so a service that names itself is taken on that network's word, and may name the buyer the order is for.
  callers:
    eshop-basket-service:
      # The commands the basket may call, a list that catches a misconfiguration: its checkout
      # submits the order, and nothing else. A system command (wow.*: delete, restore, transfers, tags) is never
      # called unless listed here.
      commands:
        - eshop-ordering.order.SubmitOrder
```

Its Kubernetes network policies are the boundary. The first lets only the gateway's pods reach the HTTP port; the second lets only the shop's own pods reach the internal port:

<!-- sample: deploy/eshop-ordering/networkpolicy.yaml -->

```yaml
# The public port: reachable from the gateway's pods alone, in any namespace, labelled
# app.kubernetes.io/component: gateway. The shop's clients reach the service through it alone. Label your gateway's pods
# so, or change the selector to match them. Enforced where the cluster's network plugin enforces NetworkPolicy.
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: eshop-ordering-service-public
  labels:
    app.kubernetes.io/name: eshop-ordering-service
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: eshop-ordering-service
  policyTypes:
    - Ingress
  ingress:
    - from:
        - namespaceSelector: {}
          podSelector:
            matchLabels:
              app.kubernetes.io/component: gateway
      ports:
        - port: http
          protocol: TCP
---
# The internal port: reachable from the platform's own pods alone, labelled app.kubernetes.io/part-of: eshop.
# It takes no token: a node names its service on each request (Wow-Service), and this policy is the
# boundary. The shop's services reach it there: this service's nodes, the basket's calls and the signals of the waits
# that cross into it. Enforced where the cluster's network plugin enforces NetworkPolicy; where it is not, keep the
# internal port off every network but the platform's.
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: eshop-ordering-service-internal
  labels:
    app.kubernetes.io/name: eshop-ordering-service
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: eshop-ordering-service
  policyTypes:
    - Ingress
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app.kubernetes.io/part-of: eshop
      ports:
        - port: internal
          protocol: TCP
```

A network plugin that does not enforce network policies ignores them. Then keep the internal port off every network but the platform's by other means, such as the nodes' firewall, because nothing else closes it.

**Encryption.** The internal port speaks plaintext HTTP/2 (h2c) by design. When traffic inside the platform must be encrypted, run the services in a service mesh with mutual TLS, such as Istio or Linkerd. Wow has no native TLS on the internal port, and none is planned.

## Restrict the command topics

Kafka is inside the platform too, and a command topic is an entrance: a record on `wow.<context>.commands` is executed without admission, so whoever can write it can name any operator, tenant and scope. Records are not signed. Use Kafka's own authentication and ACLs so that only the services that send the context commands write its command topic: its own nodes, the contexts whose sagas and timers send it commands, and ops, which sends the operators' actions. For the eShop's ordering context, whose payment service sends it commands:

```bash
kafka-acls.sh --bootstrap-server kafka:9092 --add --allow-principal User:ordering --operation Write --topic wow.eshop-ordering.commands
kafka-acls.sh --bootstrap-server kafka:9092 --add --allow-principal User:payment --operation Write --topic wow.eshop-ordering.commands
kafka-acls.sh --bootstrap-server kafka:9092 --add --allow-principal User:ops --operation Write --topic wow.eshop-ordering.commands
```

Every other principal gets no `Write` ACL on the topic. With an authorizer and `allow.everyone.if.no.acl.found=false`, the broker's default, a principal without an ACL is refused. [Kafka](kafka#topics) lists the topics each context owns and who writes them.

## Mistyped settings

A mistyped security setting would silently leave its default in place, and some defaults allow everything. So the host refuses to start when any `wow.*` key is unknown; under `wow.callers` and `wow.peers`, each entry's keys are checked too.

## Personal data in events

Events are facts. The log keeps them for good, and Wow offers neither erasing events nor crypto-shredding. Plan for that in the model:

- Keep personal data, such as names, addresses, phone numbers and free text about a person, out of events where you can. Keep it in a read model you can delete from, such as a projection's table or another service, and put an opaque id in the event.
- When decide needs personal data, let it read the data through a domain service instead of carrying it in an event.
- The operator of a commit is recorded as an opaque id. Request facts are recorded with every commit: the authenticated application and, for requests on the public port, the client's remote address and user agent, `remote_ip` and `user_agent`. An IP address and a user agent are personal data, and once written they cannot be erased. Treat the event log, and the service logs that show these facts, as holding personal data, and set the logs' retention for it. `wow.http.client-facts.enabled` (default `true`) switches the address and user agent off: with `false`, the public port records neither. Do not add further personal data to `wow.http.request-facts`.
- Snapshots, the warehouse and search indexes are derived from the log: deleting a row there does not delete the fact.

## Tenants

All the services of a system share one tenant space: a tenant id means the same customer in every service, and crosses services as it is. Resolve the caller's tenant the same way in every service. An aggregate type can be given a fixed tenant, `wow.aggregates.[<context>.<aggregate>].tenant`, which puts all its commands and reads in that tenant whoever the caller is.

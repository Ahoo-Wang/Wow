---
title: Secure a Deployment
description: How to authenticate callers, authorize commands, close the internal port, Kafka and the stores to everyone but the system's own services, and keep personal data out of a log that is never changed.
---

# Secure a Deployment

A Wow service has two doors: the public port, where callers send commands and queries, and the internal port, where the system's own nodes and services talk to each other. This page explains how to secure each, what the defaults are, and what the log means for personal data. [Identity, tenancy and access](../concepts/identity) explains the model behind it.

## Checklist

- The public port is reachable from the gateway alone.
- The internal port, Kafka and the stores are never reachable from outside the platform: network policies, as in `deploy/`, let only the platform's own pods reach them.
- The gateway overwrites the forwarded headers and the identity headers it injects; it never passes a client's on.
- A caller resolver authenticates every request to the public port; CoSec provides one.
- Commands are authorized per action, not only per route, where routes alone are not enough.
- Each service lists the system commands other services may call on its internal port, and, where it helps catch a mistake, the domain commands and models.
- Only a service's own nodes, the services that send it commands, and ops write its command topic.
- Database and broker passwords are secrets.
- Events hold no personal data you may have to erase.

## Authenticate callers

The host turns each request into a caller with its `CallerResolver`. Without one, a node in development (a profile of `wow.dev-profiles`, or an internal port on the loopback interface alone) serves every request as the anonymous caller of the default tenant and logs a warning; any other node refuses to start. A service that is anonymous on purpose says so with a `CallerResolver` bean returning `CallerResolver.ANONYMOUS`.

With CoSec on the classpath, its resolver is the host's. It reads the caller from CoSec's security context:

- **Tenant and subject** come from the principal. A request without an authenticated principal is answered 401.
- **Owner.** A principal is an owner-level caller, acting on its own aggregates, unless it has one of the roles or policies of `wow.cosec.tenant-level`: then it is a tenant-level caller, acting on every aggregate of its tenant, such as a member of staff who ships every buyer's orders.
- **Space.** A principal's spaced roles (`role@space`) are the spaces it belongs to. The `Wow-Space` header picks one; without it, a principal acts in its only space, or in none.
- **Acting elsewhere.** Only a principal with a role or policy of `wow.cosec.cross-scope` may name another tenant or scope in `Wow-Scope`.
- **Services.** A CoSec principal is never a service identity. A service names itself on the internal port alone ([Close the internal port](#close-the-internal-port)).
- **Application.** The request's `CoSec-App-Id` becomes the caller's application, and the commit's `app_id` fact, only when CoSec authorized the request through that application's role permissions. A header the client sets is not trusted on its own. Behind a CoSec gateway in inject mode, `wow.cosec.trust-injected-app` trusts the application the gateway forwards; it is off by default, and only safe when nothing but the gateway reaches the service.

## Record the client address

Each command records the client's `User-Agent` and address (`user_agent`, `remote_ip`), each cut to 512 characters. A gateway sits in front of the public port, so the host takes the address from its `X-Forwarded-For`: it sets Spring Boot's `server.forward-headers-strategy` to `framework` unless the application sets it, and logs the strategy in effect at startup. The gateway must overwrite the header a client sends. Set the strategy to `none` to record the connection's address instead.

## Authorize commands

Authorization happens at admission, before the command runs:

- **Per route.** CoSec's web filter authorizes the public routes by path and method.
- **Per action.** With `wow.cosec.authorizer.enabled: true`, CoSec also authorizes every command as its caller, whichever way it arrived. It is off by default: without it, and without an authorizer of your own, a command that passed its route is allowed. A `CommandAuthorizer` of your own replaces CoSec's; to keep CoSec's decision for some commands, inject `CoSecAuthorizers` (present when the key is on) and ask its `authorizer()`.
- **Per scope.** Whatever the authorizer says, the kernel refuses a command on an aggregate outside the caller's scope, and queries only return what the caller may see.

Commands a saga sends, compensations and scheduled commands inherit the scope and operator of the root command, which was authorized when it was admitted.

The operator's routes over parked aggregates, `/wow/ops/parked`, are off by default (`wow.ops.enabled`). Turned on, their actions are authorized as the system commands `wow.ledger.*`, and the host warns when no command authorizer would check them.

## Put the gateway in front

Wow trusts the identity the gateway forwards, so the gateway is a boundary of its own. Two requirements hold for every deployment:

1. **The public port is reachable from the gateway alone.** A client that reached the public port around the gateway could send the headers the gateway would have injected, and be anyone. The deploy manifests' network policies let only the gateway's pods reach it.
2. **The gateway overwrites the headers the service trusts.** It sets the forwarded headers, `X-Forwarded-For` and `Forwarded`, from the connection it accepted, instead of passing a client's on or appending to them, and it sets the identity headers it injects, such as CoSec inject mode's headers and `CoSec-App-Id`, removing any the client sent. Otherwise a client chooses its own address or identity.

The host cannot see the network, so it does not check either at startup.

Behind the gateway, a service need not verify a token again. Wow's platform services, the ops service, the view store and the analytics catalog, run CoSec's inject mode in their `cosec` profile: the CoSec gateway verifies each request's access token and forwards it, and the service parses the principal from it without a secret of its own (`cosec.jwt.enabled: false`), so CoSec's signing secret stays with the gateway. The parser still refuses a token that has expired.

A command sent again is the same command only under the same request id. A client that resends a command after losing its answer sends it with the same `Wow-Request-Id`, and the service answers it as a duplicate instead of running it twice. With CoSec, a command without `Wow-Request-Id` takes the request's `CoSec-Request-Id`. The CoSec gateway sets it once on each request it forwards, and a retry of the request, such as Spring Cloud Gateway's after a connection reset, resends the same one, so the service answers the retry as a duplicate. `Wow-Request-Id` wins when a request carries both.

## Close the internal port

The internal port carries forwarded commands, wait signals, synchronous calls and reads between the platform's nodes and services. The inside of the platform is trusted: the internal port takes no token, and its boundary is the network. Whoever can send a signal can fake the result of any command, so:

1. **Keep it inside the platform.** No public load balancer, ingress or port mapping points at it, and a network policy lets only the platform's own pods reach it. The same holds for Kafka and the stores.
2. **Bind it to the private interface** with `wow.internal.host`.
3. **Every request names its service.** A node or service puts its service name, `wow.service` or else `spring.application.name`, in the `Wow-Service` header of each forward, signal, call and read. A service name is letters, digits, `.`, `_` and `-`, at most 253 characters; a node whose service name is not such a name does not start. The header is a statement, not a credential: it catches a misconfiguration, such as a call a service's list does not allow, or a forward sent to another service's node.
4. **Each route takes the services it serves.** A forward, and the standby and resume routes (`GET /wow/node/standby`, `POST /wow/node/resume`), are taken only from a node of the same service: without the header the answer is 401 `wow.auth.unauthenticated`, from another service 403 `wow.auth.forbidden`. A wait signal is taken from any service that names itself, and 401 without the header. A synchronous call (`Wow-Call`) or a read is taken from any service that names itself, as that service's identity (subject `service:<name>`), and 403 for a request that names none. The public port never reads `Wow-Service`, and refuses `Wow-Call` with 403.
5. **List what each service may call, where it helps.** Under `wow.callers.<service>`, keyed by the name the caller declares, `commands` lists the commands that service may call and `reads` the models it may read. Both are optional: without `commands` a service may call every domain command, without `reads` read every model. System commands (`wow.*`, such as delete, restore or rebuild a snapshot) are never called unless listed. The lists catch a mistake; they are no boundary against a pod that lies about its name.

The eShop's ordering service puts it together. Its basket service calls one command synchronously, and nothing else; its inventory and payment services signal waits that crossed into them, which needs no entry:

<!-- sample: example/eshop/ordering/ordering-server/src/main/resources/application.yaml#internal-port -->

```yaml
wow:
  service: eshop-ordering-service
  # One node, its data in memory, for trying the service out. The `postgres` profile serves it from PostgreSQL; the
  # `kafka` profile connects it to the other contexts; without it, the node on this machine keeps its topics in its
  # JVM (wow.transport), where the inventory does not see the commands its sagas send.
  backend: memory
  internal:
    port: 9093
    # On this machine only, for trying the shop out: the basket service calls the order gateway here.
    host: 127.0.0.1
  # The basket service's checkout calls this command gateway synchronously (decision D193), on the internal port
  # alone, naming itself (Wow-Service, decision D300): the internal port is reachable by the shop's own pods alone,
  # so a service that names itself is taken on that network's word, and may name the buyer the order is for.
  callers:
    eshop-basket-service:
      # The commands the basket may call (decision D226), a list that catches a misconfiguration: its checkout
      # submits the order, and nothing else. A system command (wow.*: delete, restore, transfers, tags) is never
      # called unless listed here.
      commands:
        - eshop-ordering.order.SubmitOrder
```

Its Kubernetes network policies are the boundary. The first lets only the gateway's pods reach the HTTP port; the second lets only the shop's own pods reach the internal port:

<!-- sample: deploy/eshop-ordering/networkpolicy.yaml -->

```yaml
# The public port: reachable from the gateway's pods alone (decisions D296, D300), in any namespace, labelled
# app.kubernetes.io/component: gateway. The shop's clients reach the service through it alone. Label your gateway's pods
# so, or change the selector to match them. Enforced where the cluster's network plugin enforces NetworkPolicy.
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: eshop-ordering-public
  labels:
    app.kubernetes.io/name: eshop-ordering
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: eshop-ordering
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
# The internal port: reachable from the platform's own pods alone, labelled app.kubernetes.io/part-of: eshop (decisions
# D173, D298, D300). It takes no token: a node names its service on each request (Wow-Service), and this policy is the
# boundary. The shop's services reach it there: this service's nodes, the basket's calls and the signals of the waits
# that cross into it. Enforced where the cluster's network plugin enforces NetworkPolicy; where it is not, keep the
# internal port off every network but the platform's.
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: eshop-ordering-internal
  labels:
    app.kubernetes.io/name: eshop-ordering
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: eshop-ordering
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

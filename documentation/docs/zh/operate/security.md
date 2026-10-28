---
title: 保护部署
description: 怎样认证调用方、授权命令、只让系统自己的服务访问内部端口、Kafka 和存储，以及怎样不让个人数据进入一份永不修改的日志。
---

# 保护部署

Wow 服务有两扇门：公开端口，调用方在这里发送命令和查询；内部端口，系统自己的节点和服务在这里互相通信。本页说明怎样保护每一扇门、默认值是什么，以及日志对个人数据意味着什么。背后的模型见[身份、租户与访问](../concepts/identity)。

## 检查清单 {#checklist}

- 只有网关能访问公开端口。
- 平台之外永远访问不到内部端口、Kafka 和存储：像 `deploy/` 里那样，用网络策略只让平台自己的 Pod 访问它们。
- 网关覆盖转发头和它注入的身份头，从不把客户端发来的传下去。
- 调用方解析器认证发往公开端口的每个请求；CoSec 提供了一个。
- 只按路由授权不够的地方，命令按动作授权。
- 每个服务列出其他服务可以在它的内部端口上调用的系统命令；有助于发现错误时，也列出领域命令和模型。
- 只有服务自己的节点、给它发命令的服务和 ops 能写它的命令主题。
- 数据库和消息代理的密码是密钥。
- 事件里没有你可能不得不删除的个人数据。

## 认证调用方 {#authenticate-callers}

宿主用它的 `CallerResolver` 把每个请求变成一个调用方。没有解析器时，开发中的节点（`wow.dev-profiles` 中的某个 profile 生效，或内部端口只绑定回环接口）以默认租户的匿名调用方身份处理每个请求，并记录一条警告；其他节点拒绝启动。有意匿名的服务用一个返回 `CallerResolver.ANONYMOUS` 的 `CallerResolver` Bean 表明这一点。

类路径上有 CoSec 时，它的解析器就是宿主的解析器。它从 CoSec 的安全上下文读取调用方：

- **租户和主体标识**来自主体。没有已认证主体的请求返回 401。
- **所有者。** 主体是所有者级调用方，作用于它自己的聚合；除非它具有 `wow.cosec.tenant-level` 中的某个角色或策略，这时它是租户级调用方，作用于它租户的每个聚合，例如为每个买家发货的员工。
- **空间。** 主体带空间的角色（`role@space`）就是它所属的空间。`Wow-Space` 请求头选择其中一个；不带这个请求头时，主体在它唯一的空间中行事，或者不在任何空间中。
- **在别处行事。** 只有具有 `wow.cosec.cross-scope` 中某个角色或策略的主体，才能在 `Wow-Scope` 里指明另一个租户或作用域。
- **服务。** CoSec 主体从来不是服务身份。服务只在内部端口上声明自己（[关上内部端口](#close-the-internal-port)）。
- **应用。** 只有当 CoSec 通过该应用的角色权限授权了请求时，请求的 `CoSec-App-Id` 才成为调用方的应用，以及提交的 `app_id` 事实。客户端自己设置的请求头本身不被信任。在注入模式的 CoSec 网关之后，`wow.cosec.trust-injected-app` 会信任网关转发的应用；它默认关闭，只有当除了网关没有别的东西能访问服务时才安全。

## 记录客户端地址 {#record-the-client-address}

每条命令记录客户端的 `User-Agent` 和地址（`user_agent`、`remote_ip`），每个都截断到 512 个字符。公开端口前面总有网关，所以宿主从网关的 `X-Forwarded-For` 取地址：除非应用自己设置，宿主把 Spring Boot 的 `server.forward-headers-strategy` 设为 `framework`，并在启动时记录生效的策略。网关必须覆盖客户端发来的这个请求头。把策略设为 `none` 则记录连接的地址。

## 授权命令 {#authorize-commands}

授权在准入时、命令运行之前进行：

- **按路由。** CoSec 的 Web 过滤器按路径和方法为公开路由授权。
- **按动作。** 设置 `wow.cosec.authorizer.enabled: true` 后，无论命令从哪条路径到达，CoSec 都会以它的调用方身份为每条命令授权。它默认关闭：没有它、也没有你自己的授权器时，通过了路由的命令就被允许。你自己的 `CommandAuthorizer` 与 CoSec 的授权器一同询问，命令需要两者都允许（[组合规则](../guide/spring-boot#beans-the-application-gives)）；只应在没有 CoSec 时才决定的、按你自己的角色授权的授权器，注入 `ObjectProvider<CoSecCommandAuthorizer>`，在存在它时不提出异议，平台服务就是这样做的。CoSec 把每条命令当作它在服务 Manifest 中路由的请求来评估，方法和路径与 Web 过滤器看到的一致（`wow.lifecycle.DeleteAggregate` 是 `DELETE /shop/cart/{id}`，还有 `POST /shop/cart/wow.snapshot.rebuild`、`POST /shop/wow.schedule/{id}/enable`），所以一条策略同时覆盖两种入口。同一开关也让 CoSec 按路由的方法和路径为每次[服务路由](../reference/annotations#serviceroute)调用授权。两个授权器都按调用方发出请求的样子评估：来自它的应用（`CoSec-App-Id`），处于它的空间，所以应用角色的权限对它们生效，与对 Web 过滤器一样：打开开关绝不会拒绝 HTTP 允许的请求。Manifest 中没有路由的命令（例如调度器的 `wow.schedule.Tick`）按 `POST /{context}/{aggregate}/{id}/{完整名称}` 评估（`POST /shop/schedule/report/wow.schedule.Tick`）：为它编写这种形状的策略。按属性匹配命令的策略读取 `wow.command`，即命令的完整类型名（`shop.cart.AddItem`；系统命令以聚合类型限定，如 `shop.cart.wow.lifecycle.DeleteAggregate`），此外还有 `wow.aggregate.id`、`wow.tenant`、`wow.owner`、`wow.space` 和 `wow.creation`。
- **按作用域。** 无论授权器怎么说，内核都会拒绝作用于调用方作用域之外的聚合的命令，查询也只返回调用方可以看的东西。

Saga 发出的命令、补偿和定时命令，继承根命令的作用域和操作人，而根命令在准入时已经授权过。

运维人员操作停放聚合的路由 `/wow/ops/parked` 默认关闭（`wow.http.ops.enabled`）。打开后，它们的动作和读取按系统命令 `wow.ledger.*` 授权。没有命令授权器时，它们全部被拒绝（403 `wow.auth.forbidden`），默认从不放行，宿主在启动时发出警告。

## 把网关放在前面 {#put-the-gateway-in-front}

Wow 信任网关转发的身份，所以网关本身就是一条边界。每个部署都要满足两条要求：

1. **只有网关能访问公开端口。** 绕过网关访问到公开端口的客户端，可以自己发送本该由网关注入的请求头，冒充任何人。部署清单里的网络策略只让网关的 Pod 访问它。
2. **网关覆盖服务信任的请求头。** 它根据自己接受的连接设置转发头 `X-Forwarded-For` 和 `Forwarded`，而不是把客户端发来的传下去或在后面追加；它也设置自己注入的身份头，例如 CoSec 注入模式的请求头和 `CoSec-App-Id`，并去掉客户端发来的同名请求头。否则客户端可以自己选择地址或身份。

宿主看不到网络，所以启动时不检查这两条。

在网关之后，服务不必再校验一遍令牌。Wow 的平台服务（ops 服务、视图存储和分析目录）在 `cosec` profile 下运行 CoSec 的注入模式：CoSec 网关校验每个请求的访问令牌并转发，服务从中解析出主体，自己不持有密钥（`cosec.jwt.enabled: false`），CoSec 的签名密钥只留在网关。解析器仍会拒绝已过期的令牌。

再次发送的命令，只有带着同一个请求 ID 才是同一条命令。客户端在丢失应答后重发命令时，要带上同一个 `Wow-Request-Id`，服务会把它当作重复请求应答，而不会执行两次。使用 CoSec 时，没有 `Wow-Request-Id` 的命令取请求的 `CoSec-Request-Id` 作为请求 ID。CoSec 网关为它转发的每个请求设置一次这个头，请求的重试（例如 Spring Cloud Gateway 在连接重置后的重试）会再次发送同一个值，所以服务把重试当作重复请求应答。请求同时带有两者时，以 `Wow-Request-Id` 为准。

### 配置 CoSec Gateway {#configure-the-cosec-gateway}

CoSec Gateway 基于 Spring Cloud Gateway，Wow 的端到端测试在 eShop 和平台服务之前运行的就是它，使用下面的设置。它们是上面两条要求在这个网关上的写法：

- **它是边缘。** `server.forward-headers-strategy: none`：它从不把客户端的 `Forwarded` 或 `X-Forwarded-For` 当作自己的远端地址，而 Spring Boot 在 Kubernetes 上的默认做法会这样做。
- **它只写一次客户端地址。** 从 Spring Cloud Gateway 4.1 起，X-Forwarded 头过滤器只为 `trusted-proxies` 匹配的对端写入，所以只设 `for-append: false` 不起作用。`trusted-proxies: ".*"` 加上 `for-append: false`，把它接受的对端写成唯一的 `X-Forwarded-For`，替换客户端带来的值；`forwarded.enabled: false` 不发送服务会优先采用的 `Forwarded` 头。
- **它重试丢失的连接，从不重试回答。** `Retry` 过滤器在 `IOException` 或 `TimeoutException` 时把请求重发一次，`POST` 也一样：重试带着同一个 `CoSec-Request-Id`，或客户端的 `Wow-Request-Id`，所以服务把它当作重复请求回答。`series: INFORMATIONAL` 不匹配任何最终状态，所以不会重试任何响应。连接池中的连接最多空闲 15 秒，所以很少会复用到已经消失的 Pod 的连接。
- **它需要自己的 Redis**，用来保存策略。Wow 的服务都不连接它。

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

每条路由把一个上下文的路径前缀发往它的服务：

<!-- sample: .github/ga-e2e/gateway/gateway.yaml#routes -->

```yaml
# Each service by its context prefix.
routes:
  - id: eshop-catalog
    uri: http://eshop-catalog-service
    predicates: [Path=/eshop-catalog/**]
  - id: eshop-basket
    uri: http://eshop-basket-service
    predicates: [Path=/eshop-basket/**]
```

网关校验每个请求的令牌（`cosec.jwt`），并按它的策略授权；在它之后，CoSec 注入模式下的服务从它转发的令牌中解析出主体。CoSec 的 Redis 限流器保持关闭：配置了 Redis 时，它的 Bean 与 Spring Cloud Gateway 的冲突。

<!-- sample: .github/ga-e2e/gateway/gateway.yaml#cosec -->

```yaml
cosec:
  audit:
    kafka:
      enabled: false
  authentication:
    enabled: false
  ip2region:
    enabled: false
  # CoSec's Redis rate limiter registers a bean named redisRateLimiter, as Spring Cloud Gateway's Redis
  # auto-configuration does: with Redis configured, the image does not start unless one of them is off (see
  # https://github.com/Ahoo-Wang/CoSec/issues/1063).
  limiter:
    enabled: false
  jwt:
    algorithm: hmac256
    secret: ${COSEC_JWT_SECRET}
  authorization:
    # A principal named by the cosec.root property would bypass every policy.
    root-enabled: false
    local-policy:
      enabled: true
      init-repository: true
      force-refresh: true
      locations: file:./config/policy.json
```

一条全局策略放行任何已认证的主体，把它能做什么留给各个服务，由服务为每条命令授权：

<!-- sample: .github/ga-e2e/gateway/gateway.yaml#policy -->

```yaml
# Any authenticated principal passes; what it may do is each service's to say (the gateway authorizes coarsely).
policy.json: |
  {
    "id": "ga-e2e",
    "name": "GA end-to-end",
    "category": "ga-e2e",
    "description": "Every route for any authenticated principal",
    "type": "global",
    "tenantId": "(platform)",
    "statements": [
      { "name": "authenticated", "action": "/**", "condition": { "authenticated": {} } }
    ]
  }
```

网关的 Pod 带有 `app.kubernetes.io/component: gateway` 标签，也就是服务公开端口的网络策略所放行的标签。

## 关上内部端口 {#close-the-internal-port}

内部端口承载平台的节点和服务之间转发的命令、等待信号、同步调用和读。平台内部是受信环境：内部端口不用令牌，它的边界是网络。谁能发信号，谁就能伪造任意命令的结果，所以：

1. **把它留在平台之内。** 不能有公网负载均衡、Ingress 或端口映射指向它，并用网络策略只让平台自己的 Pod 访问它。Kafka 和存储也一样。
2. **用 `wow.internal.host` 把它绑定到内网网卡。**
3. **每个请求都声明自己的服务。** 节点或服务在每次转发、信号、调用和读的 `Wow-Service` 请求头里放上自己的服务名，即 `wow.service`，没有时为 `spring.application.name`。服务名由字母、数字、`.`、`_` 和 `-` 组成，以字母或数字开头，最多 253 个字符；服务名不符合的节点不会启动。这个请求头是声明，不是凭据：它能发现配置错误，例如某个服务的列表不允许的调用，或者发到另一个服务节点上的转发。
4. **每条路由只接受它服务的那些服务。** 转发，以及暂停和恢复路由（`GET /wow/node/standby`、`POST /wow/node/resume`），只接受同一个服务的节点：没有这个请求头时返回 401 `wow.auth.unauthenticated`，来自另一个服务时返回 403 `wow.auth.forbidden`。等待信号接受任何声明了自己的服务，没有请求头时返回 401。同步调用（`Wow-Call`）或读接受任何声明了自己的服务，并以该服务的身份（主体 `service:<name>`）处理；没有声明服务的请求返回 403。公开端口从不读取 `Wow-Service`，并以 403 拒绝 `Wow-Call`。
5. **在有帮助的地方，列出每个服务可以调用什么。** 在 `wow.callers.<service>` 下，以调用方声明的名字为键，`commands` 列出该服务可以调用的命令，`reads` 列出它可以读的模型。两者都是可选的：没有 `commands` 时，服务可以调用所有领域命令；没有 `reads` 时，可以读所有模型。系统命令（`wow.*`，例如删除、恢复、重建快照）不列出就永远不能调用。这些列表用来发现错误，挡不住谎报名字的 Pod。

eShop 的 ordering 服务把这些组合在一起。它的 basket 服务同步调用一条命令，别的都不调用；它的 inventory 和 payment 服务为跨进它们的等待发信号，这不需要配置项：

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

它的 Kubernetes 网络策略就是边界。第一条只让网关的 Pod 访问 HTTP 端口；第二条只让 eShop 自己的 Pod 访问内部端口：

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

不执行网络策略的网络插件会忽略它们。这时要用别的办法，例如节点的防火墙，让内部端口只在平台的网络上可达，因为没有别的东西关得上它。

**加密。** 内部端口按设计使用明文 HTTP/2（h2c）。平台内的流量必须加密时，把服务放进带双向 TLS 的服务网格，例如 Istio 或 Linkerd。Wow 在内部端口上没有原生 TLS，也没有这个计划。

## 限制命令主题 {#restrict-the-command-topics}

Kafka 也在平台之内，而命令主题是一个入口：`wow.<context>.commands` 上的记录不经准入就会执行，所以谁能写它，谁就能指定任意操作人、租户和作用域。记录不签名。用 Kafka 自己的认证和 ACL，让只有给这个上下文发命令的服务能写它的命令主题：它自己的节点、Saga 和定时器给它发命令的上下文，以及发送运维人员操作的 ops。对 eShop 的 ordering 上下文，payment 服务会给它发命令：

```bash
kafka-acls.sh --bootstrap-server kafka:9092 --add --allow-principal User:ordering --operation Write --topic wow.eshop-ordering.commands
kafka-acls.sh --bootstrap-server kafka:9092 --add --allow-principal User:payment --operation Write --topic wow.eshop-ordering.commands
kafka-acls.sh --bootstrap-server kafka:9092 --add --allow-principal User:ops --operation Write --topic wow.eshop-ordering.commands
```

其他主体在这个主题上都没有 `Write` ACL。启用授权器并且 `allow.everyone.if.no.acl.found=false`（代理的默认值）时，没有 ACL 的主体会被拒绝。[Kafka](kafka#topics) 列出了每个上下文拥有的主题以及谁写它们。

## 写错的设置 {#mistyped-settings}

写错的安全设置会悄悄保留默认值，而有些默认值允许一切。所以任何 `wow.*` 键未知时，宿主都拒绝启动；`wow.callers` 和 `wow.peers` 下每一项的键也会检查。

## 事件中的个人数据 {#personal-data-in-events}

事件是事实。日志永久保存它们，Wow 既不提供删除事件，也不提供加密粉碎（crypto-shredding）。在模型里要为此做好打算：

- 尽可能不让个人数据进入事件，例如姓名、地址、电话号码和关于某个人的自由文本。把它们保存在可以删除的读模型里，例如投影的表或另一个服务，并在事件里放一个不透明的 ID。
- 决定函数需要个人数据时，让它通过领域服务读取，而不是把数据带在事件里。
- 提交的操作人记录为不透明的 ID。每次提交都会记录请求事实：已认证的应用，以及对公开端口上的请求，客户端的远程地址和 User-Agent，即 `remote_ip` 和 `user_agent`。IP 地址和 User-Agent 属于个人数据，一旦写入就无法删除。把事件日志以及显示这些事实的服务日志都当作含有个人数据的数据来对待，并据此设置日志的保留期。`wow.http.client-facts.enabled`（默认 `true`）可以关闭地址和 User-Agent 的记录：设为 `false` 时，公开端口两者都不记录。不要再往 `wow.http.request-facts` 里加其他个人数据。
- 快照、数仓和检索索引都从日志派生：在那里删除一行，并不会删除事实。

## 租户 {#tenants}

一个系统的所有服务共用一个租户空间：一个租户 ID 在每个服务里都指同一个客户，并原样跨服务传递。在每个服务里都以同样的方式解析调用方的租户。可以给聚合类型指定一个固定租户 `wow.aggregates.[<context>.<aggregate>].tenant`，无论调用方是谁，它的所有命令和读都落在那个租户里。

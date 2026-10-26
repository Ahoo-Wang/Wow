---
title: 保护部署
description: 怎样认证调用方、授权命令、只让系统自己的服务访问内部端口，以及怎样不让个人数据进入一份永不修改的日志。
---

# 保护部署

Wow 服务有两扇门：公开端口，调用方在这里发送命令和查询；内部端口，系统自己的节点和服务在这里互相通信。本页说明怎样保护每一扇门、默认值是什么，以及日志对个人数据意味着什么。背后的模型见[身份、租户与访问](../concepts/identity)。

## 检查清单 {#checklist}

- 调用方解析器认证发往公开端口的每个请求；CoSec 提供了一个。
- 只按路由授权不够的地方，命令按动作授权。
- 集群之外访问不到内部端口，每个服务都有一个至少 32 个字符的集群令牌。
- 每个服务列出它在内部端口上信任的服务，以及每个服务可以调用的命令。
- 令牌和数据库密码都是密钥，用同样的方式管理。
- 事件里没有你可能不得不删除的个人数据。

## 认证调用方 {#authenticate-callers}

宿主用它的 `CallerResolver` 把每个请求变成一个调用方。没有解析器时，开发中的节点（`wow.internal.dev-profiles` 中的某个 profile 生效，或内部端口只绑定回环接口）以默认租户的匿名调用方身份处理每个请求，并记录一条警告；其他节点拒绝启动。有意匿名的服务用一个返回 `CallerResolver.ANONYMOUS` 的 `CallerResolver` Bean 表明这一点。

类路径上有 CoSec 时，它的解析器就是宿主的解析器。它从 CoSec 的安全上下文读取调用方：

- **租户和主体标识**来自主体。没有已认证主体的请求返回 401。
- **所有者。** 主体是所有者级调用方，作用于它自己的聚合；除非它具有 `wow.cosec.tenant-level` 中的某个角色或策略，这时它是租户级调用方，作用于它租户的每个聚合，例如为每个买家发货的员工。
- **空间。** 主体带空间的角色（`role@space`）就是它所属的空间。`Wow-Space` 请求头选择其中一个；不带这个请求头时，主体在它唯一的空间中行事，或者不在任何空间中。
- **在别处行事。** 只有具有 `wow.cosec.cross-scope` 中某个角色或策略的主体，才能在 `Wow-Scope` 里指明另一个租户或作用域。
- **服务。** 只有具有 `wow.cosec.service` 中某个角色或策略的主体才是服务身份，才能发起同步调用。
- **应用。** 只有当 CoSec 通过该应用的角色权限授权了请求时，请求的 `CoSec-App-Id` 才成为调用方的应用，以及提交的 `app_id` 事实。客户端自己设置的请求头本身不被信任。在注入模式的 CoSec 网关之后，`wow.cosec.trust-injected-app` 会信任网关转发的应用；它默认关闭，只有当除了网关没有别的东西能访问服务时才安全。

## 记录客户端地址 {#record-the-client-address}

每条命令记录客户端的 `User-Agent` 和地址（`user_agent`、`remote_ip`），每个都截断到 512 个字符。公开端口前面总有网关，所以宿主从网关的 `X-Forwarded-For` 取地址：除非应用自己设置，宿主把 Spring Boot 的 `server.forward-headers-strategy` 设为 `framework`，并在启动时记录生效的策略。网关必须覆盖客户端发来的这个请求头。把策略设为 `none` 则记录连接的地址。

## 授权命令 {#authorize-commands}

授权在准入时、命令运行之前进行：

- **按路由。** CoSec 的 Web 过滤器按路径和方法为公开路由授权。
- **按动作。** 设置 `wow.cosec.authorizer.enabled: true` 后，无论命令从哪条路径到达，CoSec 都会以它的调用方身份为每条命令授权。它默认关闭：没有它、也没有你自己的授权器时，通过了路由的命令就被允许。
- **按作用域。** 无论授权器怎么说，内核都会拒绝作用于调用方作用域之外的聚合的命令，查询也只返回调用方可以看的东西。

Saga 发出的命令、补偿和定时命令，继承根命令的作用域和操作人，而根命令在准入时已经授权过。

运维人员操作停放聚合的路由 `/wow/ops/parked` 默认关闭（`wow.ops.enabled`）。打开后，它们的动作按系统命令 `wow.ledger.*` 授权；没有命令授权器检查它们时，宿主会发出警告。

## 关上内部端口 {#close-the-internal-port}

内部端口承载服务之间转发的命令、等待信号、同步调用和读。谁能发信号，谁就能伪造任意命令的结果，所以：

1. **把它留在集群之内。** 不能有公网负载均衡、Ingress 或端口映射指向它，并用网络策略只让系统自己的 Pod 访问它。
2. **用 `wow.internal.host` 把它绑定到内网网卡。**
3. **在 `wow.internal.token` 里给每个服务一个集群令牌**：一个至少 32 个字符的随机密钥，在服务的所有节点上相同。节点之间的转发和信号必须带着它。没有令牌的节点只能在开发 profile 中启动，或者内部端口只绑定在回环网卡上。
4. **指明你信任的服务。** 另一个服务以它的服务身份访问内部端口：列在 `wow.http.services` 下的静态令牌，或者具有 `wow.cosec.service` 中某个角色的 CoSec 主体。服务身份可以发信号、发起调用和读；它不能转发命令，集群令牌也不能发起调用。
5. **列出每个服务可以调用的命令。** 同步调用可以调用目标的领域命令。生命周期命令和系统命令（例如删除、重建快照）必须为调用方服务显式列出，这份列表也可以收窄领域命令。用 CoSec 时，无论 `wow.cosec.authorizer.enabled` 怎么设，每次调用都由 CoSec 的策略以它的服务主体授权。

eShop 的 ordering 服务把这些组合在一起。它的 basket 服务同步调用一条命令；它的 inventory 和 payment 服务为跨进它们的等待发信号：

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

它的 Kubernetes 网络策略让集群放进来的任何人都能访问 HTTP 端口，只让商店自己的 Pod 访问内部端口：

<!-- sample: deploy/eshop-ordering/networkpolicy.yaml -->

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

不执行网络策略的网络插件会忽略它；令牌仍然有效。

**轮换集群令牌。** `wow.internal.token` 可以接受多个以逗号分隔的令牌。节点发送第一个，接受其中任何一个。轮换时，先设为 `old,new` 并滚动重启节点，再设为 `new,old` 并滚动重启，最后只留 `new`。

## 写错的设置 {#mistyped-settings}

写错的安全设置会悄悄保留默认值，而有些默认值允许一切。所以当 `wow.cosec.*`、`wow.http.services.*`、`wow.calls.*` 或 `wow.internal.*` 下出现未知的键时，宿主拒绝启动；`wow.http.services` 和 `wow.calls.contexts` 下每一项的键也会检查。其他未知的 `wow.*` 键记一条警告。

## 事件中的个人数据 {#personal-data-in-events}

事件是事实。日志永久保存它们，Wow 既不提供删除事件，也不提供加密粉碎（crypto-shredding）。在模型里要为此做好打算：

- 尽可能不让个人数据进入事件，例如姓名、地址、电话号码和关于某个人的自由文本。把它们保存在可以删除的读模型里，例如投影的表或另一个服务，并在事件里放一个不透明的 ID。
- 决定函数需要个人数据时，让它通过领域服务读取，而不是把数据带在事件里。
- 提交的操作人记录为不透明的 ID。每次提交都会记录请求事实：已认证的应用，以及对公开端口上的请求，客户端的远程地址和 User-Agent。IP 地址和 User-Agent 属于个人数据，一旦写入就无法删除。`wow.http.client-facts`（默认 `true`）可以关闭地址和 User-Agent 的记录：设为 `false` 时，公开端口两者都不记录。不要再往 `wow.http.request-facts` 里加其他个人数据。
- 快照、数仓和检索索引都从日志派生：在那里删除一行，并不会删除事实。

## 租户 {#tenants}

一个系统的所有服务共用一个租户空间：一个租户 ID 在每个服务里都指同一个客户，并原样跨服务传递。在每个服务里都以同样的方式解析调用方的租户。可以给聚合类型指定一个固定租户 `wow.aggregates.[<context>.<aggregate>].tenant`，无论调用方是谁，它的所有命令和读都落在那个租户里。

---
title: Spring Cloud 服务发现
description: 不在 Kubernetes 上时，用 wow-spring-cloud 经 Spring Cloud 的注册中心（Consul、Eureka、Nacos、Zookeeper）找到服务的对端，在注册中心登记每个节点的内部地址，并用防火墙挡住内部端口。
outline: deep
---

# Spring Cloud 服务发现

在 Kubernetes 上，服务经对端内部端口的 headless Service，用 DNS 找到对端的节点（[配置经 HTTP 访问的对端](../guide/calling-services#configure-a-peer-over-http)）。在带注册中心的虚拟机上，`wow-spring-cloud` 改从注册中心找：

- 每个节点把自己的内部地址写进实例的元数据；
- 调用对端的服务用 `wow.peer.discovery: spring-cloud` 读取对端的实例，不需要 `wow.peers.<context>.url`。

一个适配覆盖所有有 Spring Cloud starter 的注册中心：Consul、Eureka、Nacos 与 Zookeeper。注册中心由你添加的 starter 决定。同一服务的节点仍经数据库里的租约表互相发现：只有服务之间的调用经过注册中心。

模块是实验性的，与它实现的对端发现一样：其形态在 10.x 中仍可能变化。

## 添加模块 {#add-the-module}

添加本模块、所用注册中心的 starter，以及与 Spring Boot 4.0、4.1 配套的 Spring Cloud 版本系列 `2025.1.x` 的 BOM：

<!-- sample: fragment -->

```kotlin
dependencies {
    implementation(platform("org.springframework.cloud:spring-cloud-dependencies:2025.1.4"))
    implementation("me.ahoo.wow:wow-spring-cloud")
    implementation("org.springframework.cloud:spring-cloud-starter-consul-discovery")
}
```

带本模块的服务，其每个节点在 Spring Cloud 登记实例时就写入自己的内部地址。仍用别的方式找对端的节点，会在启动时记一条日志，说明如何切换。

## 找到对端 {#find-the-peers}

调用对端的服务设置发现方式，并在列出对端时不写 `url`：

<!-- sample: fragment -->

```yaml
spring:
  application:
    name: eshop-basket-service
  cloud:
    consul:
      host: consul.internal
      discovery:
        prefer-ip-address: true
wow:
  peer:
    discovery: spring-cloud
  peers:
    eshop-ordering:
      mode: service
```

客户端按对端的服务名查找它，即类路径上对端契约声明的服务名。各注册中心默认都以实例的 `spring.application.name` 登记，因此请让每个服务在注册中心的名字保持这个默认值。

| 步骤 | 发生什么 |
| --- | --- |
| 读取 | 对端的实例经应用的 `ReactiveDiscoveryClient`（默认是 Spring Cloud 自己的）读取，在对该对端的第一个请求时读取。此后每 30 秒重读一次；没有节点接受连接时立即重读（每 30 秒至多一次）。 |
| 映射 | 每个实例对应其元数据所写的基础 URL `http://<wow-internal-host>:<wow-internal-port>`。没有 `wow-internal-host` 的实例取它自己的主机。 |
| 跳过 | 元数据没写内部端口，或写的是别的服务、没写服务（`wow-service`）的实例被跳过，每个服务只警告一次。 |
| 保留 | 读取失败、超过 5 秒或读到零个实例时，保留上一次读到的实例。 |
| 调用 | 请求依次发往各节点。不接受连接，或答复正在关闭、处于待命的节点，在同一次尝试中被跳过，换下一个；因此注册中心跟不上滚动发布，也不会拖慢调用。 |

对端的 `url` 仍优先于注册中心。

这种发现方式没有 `wow.peer.spring-cloud.*` 配置项。它需要 Spring Cloud 的响应式发现客户端，各 starter 都会提供，除非 `spring.cloud.discovery.reactive.enabled` 为 `false`：没有它时，`wow.peer.discovery: spring-cloud` 的节点拒绝启动，并指出这个键。

## 节点登记什么 {#what-a-node-registers}

| 元数据键 | 值 |
| --- | --- |
| `wow-service` | 节点所属的服务：`wow.service`，否则 `spring.application.name` |
| `wow-internal-host` | 节点内部地址的主机 |
| `wow-internal-port` | 节点内部地址的端口 |

内部地址就是节点在租约成员中告诉本服务其他节点的地址：`wow.node.address`，否则是内部服务器绑定的主机与端口。因此注册中心写的是节点自己的成员访问它的地址，而不是 Spring Cloud 自行探测的地址。在有多块网卡的机器上，把 `wow.node.address`（或 `wow.internal.host`）设为其他机器能访问的私有地址。

Spring Cloud 在发送每次登记之前宣告它，时机在公开服务器启动之后，因此内部端口已经绑定，系统选定的端口也不例外。Consul、Nacos 与 Zookeeper 发送登记信息里的元数据，节点就把这些键写在那里。Eureka 更早就复制了元数据，所以节点同时把这些键交给 Eureka 的 `ApplicationInfoManager`。登记信息不接受元数据时，节点拒绝启动。

不是由 Wow 节点自己登记的实例，在注册中心自己的元数据配置里写这三个键：`spring.cloud.consul.discovery.metadata.*`、`eureka.instance.metadata-map.*`、`spring.cloud.nacos.discovery.metadata.*` 或 `spring.cloud.zookeeper.discovery.metadata.*`。键用短横线，因为 Consul 不接受带点的元数据键。

注册中心的健康检查访问公开端口，与任何 Spring Boot 应用一样。除非 `spring.cloud.consul.discovery.query-passing` 为 `true`，Consul 也返回检查失败的实例。客户端反正会跳过不答复的节点，但只读通过检查的实例，就不必去试它。

## 用防火墙挡住内部端口 {#keep-the-internal-port-behind-a-firewall}

内部端口不用令牌：每个调用方声明自己的服务名，端口照单全收（[关上内部端口](./security#close-the-internal-port)）。在 Kubernetes 上，边界是 NetworkPolicy；离开 Kubernetes，边界就是网络：每台机器的防火墙与云上的安全组。把内部端口登记在共享的注册中心里，能读注册中心的人都会知道它的地址，所以网络必须拒绝其他所有人：

1. **只允许平台自己的机器访问内部端口。** 在每个节点上，以内部端口 9093、平台机器在 `10.0.1.0/24` 为例：

   ```bash
   iptables -A INPUT -p tcp --dport 9093 -s 10.0.1.0/24 -j ACCEPT
   iptables -A INPUT -p tcp --dport 9093 -j DROP
   ```

   在云上，在节点的安全组里只对平台自己的安全组放行这个端口，不对比它更宽的地址段放行。
2. **把它绑定到私有网卡**，用 `wow.internal.host`；有公网地址的机器上，绝不绑定到所有网卡。
3. **限制谁能读注册中心**，用注册中心自己的认证（Consul 的 ACL、Nacos 的鉴权），让其中的内部地址列表留在平台之内。
4. **让公开端口留在网关之后**，与 Kubernetes 上一样（[把网关放在前面](./security#put-the-gateway-in-front)）。

## 在非 Wow 应用中解析 `lb://` {#load-balancer}

用 `WowClient` 调用 Wow 服务、自己却不是 Wow 服务的应用，可以经 Spring Cloud LoadBalancer 找到服务的实例。添加 `spring-cloud-starter-loadbalancer`，并把 `LoadBalancedEndpoints` 交给客户端，每个上下文的服务写作 `lb://<服务>`：

<!-- sample: fragment -->

```kotlin
import me.ahoo.wow.client.WowClient
import me.ahoo.wow.spring.cloud.LoadBalancedEndpoints
import java.net.URI

val client = WowClient.builder()
    .endpoints(LoadBalancedEndpoints.of(loadBalancers, mapOf("eshop-catalog" to URI.create("lb://eshop-catalog-service"))))
    .service("storefront-app")
    .build()
```

`loadBalancers` 是应用的 `ReactiveLoadBalancer.Factory<ServiceInstance>` bean。负载均衡器为每次尝试选一个实例，所以重试可能到达另一个实例：

- service 模式下，客户端调用所选实例元数据写的内部地址。
- user 模式下（`user(credential)`），它调用实例自己的 URI，即公开端口。

只有平台自己的服务以 service 模式调用：哪种调用方走哪里，见[从其他服务调用 Wow](../guide/calling-from-other-services)。

## Spring Cloud 的其余部分 {#the-rest-of-spring-cloud}

| 部分 | 与 Wow 一起 |
| --- | --- |
| Spring Cloud Gateway | 公开端口前的网关；CoSec 的网关基于它构建（[把网关放在前面](./security#put-the-gateway-in-front)） |
| Spring Cloud Config、Nacos 配置中心 | `wow.*` 键可以来自那里；节点不认识的键照样拒绝启动（[写错的设置](./security#mistyped-settings)） |
| 链路追踪 | 与任何 Spring Boot 应用一样：`wow-observability` 的 span 经应用的 OpenTelemetry 发出（[指标与追踪](./observability#metrics-and-traces)） |
| Spring Cloud Stream | 不适配：Wow 的主题走它自己的传输（[Kafka](./kafka)） |
| Wow 服务之间的 LoadBalancer 或 OpenFeign | 不用于命令：Wow 服务把每条命令路由到持有其聚合的节点，并用 `CommandClient` 调用对端（[调用其他服务](../guide/calling-services)） |

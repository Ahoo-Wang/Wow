---
title: 不用 Spring 运行
description: 在 10.0 中，Wow 的哪些部分无需 Spring 就能运行：领域、它的测试以及对其他服务的调用；以及为什么提供服务仍然需要 Spring Boot 宿主。
---

# 不用 Spring 运行

Wow 的运行时不依赖 Spring：内核、运行时和后端都是纯 Kotlin，Spring Boot 只是组合它们的一种宿主。在 10.0 中，它是唯一受支持的宿主。本页说明没有它时什么能运行、什么不能。

## 不用 Spring {#without-spring}

- **领域模块。** 组成服务的 api 和 domain 模块依赖 `wow-api` 和 `wow-kernel`，编译器为它们生成的模型不使用任何 Spring 类型。领域 jar 可以与任何 JVM 应用共享。
- **它的测试。** `wow-test` 的 `aggregateSpec`、`sagaSpec` 和处理器 spec 在内存存储上、在普通的 JUnit 测试中运行生成的模型（[测试领域代码](./testing)）。
- **调用其他服务。** `WowClient` 基于 JDK 的 `HttpClient`，在类路径上没有 Spring 的情况下，向 `*-api` jar 中指明的对端发送命令，并通过 HTTP 读取它们的快照、事件、数据集和状态：

<!-- sample: fragment -->

```kotlin
val client = WowClient.builder()
    .endpoints(EndpointResolver.of(emptyMap(), mapOf("ordering" to URI.create("http://ordering:9090"))))
    .service("basket-service")
    .build()
client.commands.of(caller).wait(SubmitOrder(items), OrderingContract.Order.ref(tenant, orderId))
```

  Java 代码通过 `JavaCommandClient.of(client.commands)` 和 `JavaQueryApi.of(client.queries)` 使用它（[调用其他服务](./calling-services)）。

## 仍然需要宿主的部分 {#what-still-takes-the-host}

提供服务，即它的路由、带订阅、定时器和集群的节点，以及它的读取侧，是许多部分的组合；在 10.0 中，组合它们的类是内部 API（`@InternalWowApi`）：服务节点、查询引擎，以及提供状态读取和查询的读取门面。应用不选择使用内部 API 就无法用它们构建节点，而任何版本都不承诺保留内部 API（[API 级别](../reference/api-levels)）。HTTP 路由 `wow-http` 构建在 Spring WebFlux 之上。

所以服务运行在 Spring Boot 宿主上（[在 Spring Boot 上运行](./spring-boot)），由它把配置映射到这些部分，并负责它们的生命周期。供其他宿主使用的公开组合 API 可能会在之后的某个 10.x 版本中提供；在此之前，无法使用 Spring Boot 的应用把它的领域和客户端留在 Wow 上，并通过旁边一个它自己的 Spring Boot 服务来提供服务。

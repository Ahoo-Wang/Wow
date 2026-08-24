---
title: 接入现有 Spring Boot 项目
description: 在已有 Gradle、Kotlin 和 Spring Boot 项目中接入 Wow，并验证代码生成、命令路由与最小运行链路。
outline: deep
---

# 接入现有 Spring Boot 项目

项目模板适合新项目；已有 Spring Boot 服务应按本页接入。目标不是一次引入全部基础设施，而是先用内存实现证明以下链路：

```text
KSP 元数据生成 → Spring 自动装配 → HTTP 命令 → 聚合处理 → 事件/快照
```

## 版本基线

以下版本与当前 Wow `8.11.6` 源码基线一致：

| 组件 | 版本 |
| --- | --- |
| JDK | 17+ |
| Wow | `8.11.6` |
| Spring Boot | `4.1.1` |
| Kotlin | `2.4.10` |
| KSP | `2.3.11` |
| CosId | `3.2.1` |

使用其他 Wow 版本时，应同时查看对应 tag 的 `gradle/libs.versions.toml` 和发布说明，不要只替换单个依赖版本。

## 1. 配置构建

下面是单模块 Kotlin 项目的最小 `build.gradle.kts`。多模块项目可把命令和事件放在 `api`，把聚合和领域测试放在 `domain`，把 Starter 与 WebFlux 放在 `server`；每个包含 Wow 注解模型的模块都必须应用 KSP 并引入 `wow-compiler`。

```kotlin
plugins {
    id("org.springframework.boot") version "4.1.1"
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.spring") version "2.4.10"
    id("com.google.devtools.ksp") version "2.3.11"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation(platform("me.ahoo.wow:wow-bom:8.11.6"))
    ksp(platform("me.ahoo.wow:wow-bom:8.11.6"))

    ksp("me.ahoo.wow:wow-compiler")
    implementation("me.ahoo.wow:wow-spring-boot-starter")
    implementation("me.ahoo.wow:wow-spring-boot-starter") {
        capabilities {
            requireCapability("me.ahoo.wow:webflux-support")
        }
    }
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("me.ahoo.cosid:cosid-spring-boot-starter:3.2.1")

    testImplementation("me.ahoo.wow:wow-test")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

Spring Boot BOM 对齐 Spring 生态依赖，`wow-bom` 只负责对齐 Wow 模块；两者都需要保留。Starter 的能力是 Gradle Feature Variant，只有请求 `webflux-support` 才会引入 Wow 的命令和查询路由处理器。更多后端见 [Spring Boot Starter](./extensions/spring-boot-starter.md)。

::: warning Maven 边界
仓库当前可验证的自动元数据生成链路是 Gradle + KSP。Maven 可以声明 Wow 运行时依赖，但本站没有经过验证的等价 Maven 代码生成流程。依赖自动 OpenAPI 和处理器元数据时，请使用 Gradle，或在发布前自行验证生成的 `META-INF/wow-metadata.json`。
:::

## 2. 使用首跑配置

Wow 的生产默认值倾向 Kafka 和 MongoDB。没有对应依赖时，必须显式切换到内存实现；`PrepareKey` 只支持 MongoDB 或 Redis，因此首跑时关闭。

```yaml
spring:
  application:
    name: demo-service

cosid:
  machine:
    enabled: true
    distributor:
      type: manual
      manual:
        machine-id: 1
  generator:
    enabled: true

wow:
  prepare:
    enabled: false
  command:
    bus:
      type: in_memory
  event:
    bus:
      type: in_memory
  eventsourcing:
    store:
      storage: in_memory
    snapshot:
      storage: in_memory
      strategy: all
    state:
      bus:
        type: in_memory
```

该配置只用于本地接入验证：进程退出后数据消失，也不支持多实例消息投递和通用条件查询。
`manual.machine-id` 也只适合单实例首跑；部署多个实例时必须改用能保证 machine-id 唯一性的分配器。

## 3. 添加领域模型

按 [聚合建模](./modeling.md) 定义：

1. 带 `@BoundedContext` 的上下文声明；
2. 命令、领域事件和 `@CommandRoute`；
3. 带 `@AggregateRoot`、`@OnCommand` 的命令聚合；
4. 带 `@OnSourcing` 的状态聚合；
5. 至少一个 `AggregateSpec`。

不要在 Controller 或数据库脚本里复制业务不变量。HTTP 层只负责把命令交给 Wow，聚合负责业务决策，状态只能由领域事件改变。

## 4. 验证代码生成

```shell
./gradlew clean kspKotlin test
test -s build/generated/ksp/main/resources/META-INF/wow-metadata.json
```

第二条命令无输出且退出码为 `0`，表示 KSP 已生成非空元数据。多模块项目应检查实际应用 KSP 的 `api`、`domain` 或 `server` 模块，例如：

```shell
test -s domain/build/generated/ksp/main/resources/META-INF/wow-metadata.json
```

运行时 `MetadataSearcher` 会合并 classpath 中所有 `META-INF/wow-metadata.json`。不要手写该文件，也不要把 `build/` 产物提交到仓库。

## 5. 启动并验证路由

```shell
./gradlew bootRun
```

打开 [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)，确认能看到聚合的命令和状态端点。然后像 [快速上手](./getting-started.md#提交第一条真实命令) 一样提交命令并读取版本化状态。

## 完成门禁

只有以下条件同时成立，才算接入完成：

- `test` 通过，领域规则由 `AggregateSpec` 验证；
- 每个注解模型模块都生成了 `META-INF/wow-metadata.json`；
- 应用启动时没有缺少 Kafka、MongoDB 或路由处理器的装配错误；
- 第一条命令不会因 `GlobalIdGenerator` 未初始化而失败；
- Swagger UI 中存在预期命令路由；
- 一条带固定 `requestId` 的命令返回成功阶段；
- 可通过状态端点读回事件溯源后的状态。

如果服务能启动但路由不存在，先按 [故障排查：元数据或生成代码缺失](./troubleshooting.md#元数据或生成代码缺失) 检查 KSP，而不是手写 Controller 绕过问题。

## 下一步

- 将单模块拆成 `api`、`domain`、`server`：参考 [模块依赖](./advanced/module-dependencies.md)。
- 切换真实消息和存储后端：参考 [Spring Boot Starter](./extensions/spring-boot-starter.md)。
- 明确命令完成语义：参考 [命令网关](./command-gateway.md)。
- 建立应用发布门禁：参考 [Wow 应用测试](./application-testing.md)。
- 为持久化事件建立升级策略：参考 [事件演进](./advanced/event-evolution.md)。

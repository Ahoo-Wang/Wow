---
title: 快速上手
description: 使用 Wow 项目模板快速创建基于 DDD 的微服务项目。
---

# 快速上手

> 使用 [Wow 项目模板](https://github.com/Ahoo-Wang/wow-project-template) 快速创建基于 _Wow_ 框架的 _DDD_ 项目。

本页的目标是完成一个最小垂直切片：启动服务、打开 Swagger UI，并用领域测试验证“命令 → 事件 → 状态”。

## 开始前

- JDK 17 或更高版本。
- Git。
- 使用仓库中的 Gradle Wrapper，无需全局安装 Gradle。
- 默认的模板配置使用内存总线、事件存储和快照存储，首次运行不需要 Kafka、MongoDB 或 Redis。

::: warning 先确认版本
Wow 项目模板独立演进，不保证与本站对应的 Wow 源码 tag 同步。创建项目后，先检查模板的 [`gradle/libs.versions.toml`](https://github.com/Ahoo-Wang/wow-project-template/blob/main/gradle/libs.versions.toml)，再根据选定的 [Wow Release](https://github.com/Ahoo-Wang/Wow/releases) 固定版本。
:::

## 10 分钟最短路径

1. 从模板创建仓库并克隆到本地。
2. 将 `settings.gradle.kts` 中的 `rootProject.name` 改为项目名称。
3. 运行领域测试并启动服务：

```shell
./gradlew :domain:check
./gradlew :server:run
```

4. 访问 [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)。

看到领域测试通过且 Swagger UI 可访问，就完成了首次验证。下文用于理解并替换模板中的 `Demo` 模型。

## 创建项目

[![Use this template](https://img.shields.io/badge/Use%20this%20template-2ea44f?style=for-the-badge&logo=github)](https://github.com/new?template_name=wow-project-template&template_owner=Ahoo-Wang)

点击上方按钮即可从 [Wow 项目模板](https://github.com/Ahoo-Wang/wow-project-template) 创建你自己的项目仓库，然后克隆到本地。

- 修改 `settings.gradle.kts` 文件，将 `rootProject.name` 修改为项目名称
- 修改 `api/{package}/DemoService`
- 修改 `domain/{package}/DemoBoundedContext`


## 项目结构

| 目录/文件 | 职责 |
| --- | --- |
| `api` | 定义命令、领域事件和查询视图模型，作为模块间的发布语言 |
| `domain` | 实现聚合、业务不变量、溯源函数和领域测试 |
| `server` | 装配领域模块和 Wow 扩展，提供应用启动入口与 `Dockerfile` |
| `config` | 保存可版本化的应用与环境配置起点 |
| `client` | 使用 [fetcher-generator](https://github.com/Ahoo-Wang/fetcher) 生成类型安全的 TypeScript 客户端 |
| `code-coverage-report` | 聚合测试覆盖率报告与门禁 |
| `dependencies` / `bom` | 集中管理依赖约束和 BOM |
| `gradle/libs.versions.toml` | 固定 Wow 与第三方依赖版本 |
| `deploy` | Kubernetes 部署清单；使用前必须按目标环境评审 |
| `document` | 上下文映射、UML 与其他项目级设计资料 |

## 接入外部基础设施（可选）

首次运行请保留模板的 `in_memory` 配置。只有当持久化、多实例消息投递或特定查询后端成为实际需求时，再引入对应扩展。

1. 使用 _Kafka_ 作为消息引擎：命令总线以及事件总线

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-kafka")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-kafka'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-kafka</artifactId>
    <version>${wow.version}</version>
</dependency>
```
:::

2. 使用 _MongoDB_ 作为事件存储以及快照存储

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-mongo")
implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-mongo'
implementation 'org.springframework.boot:spring-boot-starter-data-mongodb-reactive'
```
```xml [Maven]
  <dependencies>
    <dependency>
        <groupId>me.ahoo.wow</groupId>
        <artifactId>wow-mongo</artifactId>
        <version>${wow.version}</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-mongodb-reactive</artifactId>
    </dependency>
  </dependencies>
```
:::

3. 使用 [CosId](https://github.com/Ahoo-Wang/CosId) 作为全局、聚合根 ID 生成器

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.cosid:cosid-mongo")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.cosid:cosid-mongo'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.cosid</groupId>
    <artifactId>cosid-mongo</artifactId>
    <version>${cosid.version}</version>
</dependency>
```
:::

## 外部基础设施配置示例

下例用 Kafka 和 MongoDB 替换首跑时的内存实现。它是配置起点，不是可直接复制的生产配置；认证、TLS、容量、备份恢复和告警需按环境单独设计。

```yaml {20,23,29,34}
management:
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  endpoints:
    web:
      exposure:
        include:
          - health
          - wow
          - cosid
          - cosidGenerator
          - cosidStringGenerator
springdoc:
  show-actuator: true
spring:
  application:
    name: <your-service-name>
  mongodb:
    uri: <mongodb-uri>

cosid:
  machine:
    enabled: true
    distributor:
      type: mongo
  generator:
    enabled: true
wow:
  kafka:
    bootstrap-servers: <kafka-bootstrap-servers>
```

## 启动服务

```shell
./gradlew :server:run
```

![启动服务](/images/getting-started/run-server.png)

> 访问：[http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

![Swagger-UI](/images/getting-started/swagger-ui.png)

## 领域建模

::: tip 聚合模式
接下来的案例中，我们将使用[聚合模式](modeling)来建模。
:::

### 命令聚合根

*命令聚合根* 负责接收命令处理函数，执行相应的业务逻辑，并返回领域事件。

```kotlin {2,5}
@Suppress("unused")
@AggregateRoot
class Demo(private val state: DemoState) {

    @OnCommand
    fun onCreate(command: CreateDemo): DemoCreated {
        return DemoCreated(
            data = command.data,
        )
    }

    @OnCommand
    fun onUpdate(command: UpdateDemo): DemoUpdated {
        return DemoUpdated(
            data = command.data
        )
    }
}
```

### 状态聚合根

*状态聚合根* 负责维护聚合状态数据，接收并处理领域事件并变更聚合状态数据。

::: warning 
状态聚合根 `setter` 访问器设置为 `private`，避免命令聚合根直接变更聚合状态数据。
:::

```kotlin {3,5}
class DemoState(override val id: String) : Identifier {
    var data: String? = null
        private set

    @OnSourcing
    fun onCreated(event: DemoCreated) {
        data = event.data
    }

    @OnSourcing
    fun onUpdated(event: DemoUpdated) {
        data = event.data
    }
}
```

## 编写单元测试

为了保证代码质量，我们需要编写单元测试来验证聚合根的行为是否符合预期。

### 测试聚合根

```kotlin
class DemoSpec : AggregateSpec<Demo, DemoState>({
  on {
    val create = CreateDemo(
      data = "data"
    )
    whenCommand(create) {
      expectNoError()
      expectEventType(DemoCreated::class)
      expectState {
        data.assert().isEqualTo(create.data)
      }
      fork {
        val update = UpdateDemo(
          data = "newData"
        )
        whenCommand(update) {
          expectNoError()
          expectEventType(DemoUpdated::class)
          expectState {
            data.assert().isEqualTo(update.data)
          }
        }
      }
    }
  }
})
```

## 验证改动

在模板创建的应用仓库中，先运行与领域模型直接相关的最小检查：

```shell
./gradlew :domain:check
./gradlew :domain:jacocoTestCoverageVerification
./gradlew detekt
```

如果你要修改 Wow 框架本身，请改读[贡献者指南](../onboarding/contributor-guide.md)和[测试运行体系](./test-runtime.md)。应用项目应根据目标镜像仓库和运行环境单独设计发布与部署，不要直接复制绑定特定云厂商或凭据的流水线。

## 下一步

- 先改造领域模型：[聚合建模](./modeling.md)
- 理解写入 API 与完成阶段：[命令网关](./command-gateway.md)
- 建立读模型：[投影](./projection.md)与[查询服务](./query.md)
- 切换外部存储或总线：[配置](./configuration.md)与[扩展](./extensions/spring-boot-starter.md)

---
title: Wow 应用测试
description: 为使用 Wow 的业务应用建立领域、HTTP、真实适配器、恢复与安全测试门禁。
outline: deep
---

# Wow 应用测试

本页面向使用 Wow 构建业务系统的团队。目标不是复用 Wow 框架仓库的测试任务，而是证明你的领域模型、生成元数据、运行时装配和生产适配器能够一起工作。

## 测试分层

| 层次 | 证明什么 | 推荐入口 |
| --- | --- | --- |
| 领域规格 | 命令、业务拒绝、事件和溯源状态正确 | `AggregateSpec`、`SagaSpec` |
| 编译契约 | KSP 生成非空 `META-INF/wow-metadata.json` | `clean kspKotlin test` |
| HTTP 垂直切片 | Spring 装配、WebFlux 路由、等待阶段和状态读取闭环 | `@SpringBootTest` + `WebTestClient` |
| 真实适配器 | 生产 EventStore、SnapshotStore、Broker 与序列化契约 | Testcontainers 或隔离测试环境 |
| 恢复与升级 | 重启、历史事件、快照、投影和事件 revision 可恢复 | 备份恢复/重放测试 |
| 安全边界 | 匿名、越权、跨租户和原始查询入口均 fail closed | Spring Security/CoSec 集成测试 |

日常提交应先运行最窄的业务模块：

```shell
./gradlew :domain:test
./gradlew :server:test
./gradlew check
```

将 `domain` 和 `server` 替换为应用的实际模块名。

## 1. 领域规格

每条聚合规则至少覆盖：

- 成功命令产生的事件及最终状态；
- 业务规则拒绝路径；
- 创建、更新、删除和恢复等必要状态分支；
- 相同历史事件重放得到相同状态；
- Saga 为源事件产生正确且幂等的下游命令。

具体 DSL 见[测试套件](./test-suite.md)。

## 2. 元数据门禁

每个包含 Wow 注解模型的模块都必须生成元数据：

```shell
./gradlew clean kspKotlin test
test -s domain/build/generated/ksp/main/resources/META-INF/wow-metadata.json
```

不要手写或提交生成文件。多模块应用应逐个检查实际应用 KSP 的模块。

## 3. 最小 HTTP 垂直切片

下面的测试使用内存适配器，不需要 Docker。把路由和请求体替换为你的首个聚合：

```kotlin
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WowCommandFlowIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `http command reaches sourced state`() {
        val aggregateId = "it-${UUID.randomUUID()}"
        val client = WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:$port")
            .build()

        client.post()
            .uri("/tenant/test/demo")
            .header("Command-Wait-Stage", "SNAPSHOT")
            .header("Command-Aggregate-Id", aggregateId)
            .header("Command-Request-Id", "request-$aggregateId")
            .bodyValue(mapOf("data" to "integration"))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.succeeded").isEqualTo(true)
            .jsonPath("$.stage").isEqualTo("SNAPSHOT")
            .jsonPath("$.aggregateId").isEqualTo(aggregateId)

        client.get()
            .uri("/tenant/test/demo/$aggregateId/state/1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.id").isEqualTo(aggregateId)
            .jsonPath("$.data").isEqualTo("integration")
    }
}
```

该测试证明 KSP 元数据、Spring 装配、路由、命令处理、`SNAPSHOT` 等待和历史状态重建已连通。它不证明 Kafka 投递、持久化存储、重启恢复或生产鉴权。

## 4. 真实适配器与重启

对生产使用的每个 Adapter 增加容器或隔离环境测试，并至少验证：

1. 使用与生产一致的 Starter capabilities 和配置；
2. 命令写入真实 EventStore，重启应用后仍能读取状态；
3. 相同 `requestId` 不会重复执行；
4. Broker 重投不会重复更新投影或调用外部副作用；
5. 快照与完整事件重放结果一致；
6. 投影重建、消费者位点和补偿任务有可重复流程。

## 5. 安全与隔离

受保护应用至少保留以下反例测试：

| 场景 | 期望 |
| --- | --- |
| 匿名访问受保护命令/查询 | `401` 或 `403` |
| 伪造 tenant、owner 或 space | 被拒绝，不能扩大作用域 |
| 缺少主体 ABAC 标签 | fail closed，不得退化为 `MatchAllFilter` |
| 跨租户/拥有者查询 | 不返回任何越权记录 |
| 普通请求访问原始 `*QueryServiceFactory` | 没有可达入口 |

完整安全边界见[数据权限](./data-access.md#必须完成的安全闭环)。

## 6. 发布完成门禁

- 领域成功、拒绝和恢复分支通过；
- KSP 元数据非空且进入运行时；
- HTTP 垂直切片通过；
- 真实存储重启后状态可恢复；
- 重复投递、版本冲突和外部依赖失败路径通过；
- 鉴权、租户隔离和 ABAC 反例通过；
- 备份、恢复、重放和回滚演练有证据；
- 目标环境中的 Trace、指标和告警可观测。

修改 Wow 框架本身时，再使用[框架测试与基准](./test-runtime.md)中的仓库任务。

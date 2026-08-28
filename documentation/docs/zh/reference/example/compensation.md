---
title: 事件补偿示例
description: 运行补偿服务，验证 Dashboard、管理端点、通知与部署恢复闭环。
outline: deep
---

# 事件补偿示例

[`compensation`](https://github.com/Ahoo-Wang/Wow/tree/main/compensation) 是可运行的 Wow 应用和运营控制台。本页只负责运行与运营验证；即时重试、`ExecutionFailed` 状态机和重放语义见[事件补偿指南](../../guide/event/compensation.md)，完整属性见[事件补偿配置](../config/compensation.md)。

## 模块与验证基线

| 模块 | 运行职责 |
| --- | --- |
| `wow-compensation-api` | 命令、事件、状态与查询契约 |
| `wow-compensation-domain` | `ExecutionFailed` 聚合与退避计算 |
| `wow-compensation-core` | 失败捕获、结果写回与原事件重放 |
| `wow-compensation-server` | 快照查询、调度、OpenAPI、通知，以及存在前端构建产物时的 Dashboard 托管 |
| `dashboard` | 失败队列、详情与人工操作 |

先验证领域、核心与控制台：

```bash
./gradlew :wow-compensation-domain:check :wow-compensation-core:check
pnpm --dir compensation/dashboard exec vitest run
```

`ExecutionFailedSpec` 覆盖 prepare、force prepare、成功、再次失败和规格变更；`CompensationFilterTest` 覆盖过滤器错误边界；Dashboard 测试覆盖队列条件与操作状态。命令成功只证明这些本地 gate，不证明真实消息、存储、通知或部署环境。

## 本地服务启动、健康与路由验证

当前 `:wow-compensation-server:run` 的默认 JVM 参数会在 5555 开启无认证、无 TLS 的 JMX。最小安全的本地路由验证先生成 distribution，再用普通 `java` 只绑定 loopback。`installDist` 只复制已经存在的 `compensation/dashboard/dist`，不会构建前端；当前端产物不存在时，这条流程不验证 Dashboard 静态资源。

```bash
./gradlew :wow-compensation-server:installDist

SERVER_PORT=18083 \
SERVER_ADDRESS=127.0.0.1 \
SPRING_AUTOCONFIGURE_EXCLUDE='org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration,org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration,org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration,org.springframework.boot.mongodb.autoconfigure.MongoReactiveAutoConfiguration' \
COSID_MACHINE_DISTRIBUTOR_TYPE=manual \
COSID_MACHINE_DISTRIBUTOR_MANUAL_MACHINE_ID=1 \
WOW_COMPENSATION_SCHEDULER_ENABLED=false \
WOW_COMPENSATION_WEBHOOK_WEIXIN_URL=false \
WOW_KAFKA_ENABLED=false \
WOW_COMMAND_BUS_TYPE=in_memory \
WOW_EVENT_BUS_TYPE=in_memory \
WOW_EVENTSOURCING_STATE_BUS_TYPE=in_memory \
WOW_EVENTSOURCING_STORE_STORAGE=in_memory \
WOW_EVENTSOURCING_SNAPSHOT_STORAGE=in_memory \
WOW_PREPARE_ENABLED=false \
WOW_MONGO_ENABLED=false \
WOW_REDIS_ENABLED=false \
WOW_ELASTICSEARCH_ENABLED=false \
java \
  -Dspring.config.location=file:compensation/wow-compensation-server/src/main/resources/application.yaml \
  -cp 'compensation/wow-compensation-server/build/install/wow-compensation-server/lib/*' \
  me.ahoo.wow.compensation.server.CompensationServerKt
```

预期日志包含 `Netty started on port 18083` 和 `Started CompensationServerKt`。在另一个终端验证同一地址与端口：

```bash
curl -fsS http://127.0.0.1:18083/actuator/health/liveness
curl -fsS http://127.0.0.1:18083/v3/api-docs | \
  jq -r '.paths["/execution_failed/{id}/prepare_compensation"].put.operationId'
```

预期分别得到 `{"status":"UP"}` 和 `compensation.execution_failed.prepare_compensation`。这组检查只验证服务启动、健康端点和 prepare 路由存在；它没有请求 Dashboard 静态资源，也没有发送补偿命令或执行状态转换，因此不验证 Dashboard 或本地状态机。该模式还会在进程退出后丢失数据并禁用自动调度，不是持久恢复证明。

Dashboard 需要单独启动并验证：

```bash
pnpm --dir compensation/dashboard dev
```

## Dashboard

当前 Dashboard 提供以下队列：

| 队列 | 条件 |
| --- | --- |
| **To Retry** | `RECOVERABLE` / `UNKNOWN`、低于重试上限，且为 `FAILED` 或已超时 `PREPARED` 的记录 |
| **Executing** | 尚未超时的 `PREPARED` |
| **Next Retry** | 已到 `nextRetryAt` 的自动调度候选 |
| **Non Retryable** | 已达到普通重试上限的活动记录 |
| **Succeeded** | `SUCCEEDED` 历史记录 |
| **Unrecoverable** | `UNRECOVERABLE` 活动记录 |

列表支持按 execution ID、事件 ID、聚合 ID、聚合 context/name、processor context/name 精确筛选。详情页展示错误与堆栈、事件和聚合身份、租户、函数、恢复性、RetrySpec、时间、状态及分页事件流历史。

可用操作：

- **Prepare compensation**：普通准备，受状态、超时和重试上限约束；
- **Force prepare**：经确认越过重试上限，但不越过成功状态或未超时的 `PREPARED`；
- **Apply retry spec**：修改非负的 `maxRetries`、`minBackoff` 与 `executionTimeout`；
- **Mark recoverable**：修改恢复性并改变自动调度资格；
- **Change function**：修改 context、processor、函数名与 `EVENT` / `STATE_EVENT` 类型。

当前 UI 不提供删除或恢复已删除聚合的按钮，也没有定义运营角色、审批流或审计保留策略。部署方必须在网络、认证、授权与审计层提供这些控制。

![Event-Compensation-Dashboard](/images/compensation/dashboard.png)

![Event-Compensation-Dashboard-Apply-Retry-Spec](/images/compensation/dashboard-apply-retry-spec.png)

![Event-Compensation-Dashboard-Succeeded](/images/compensation/dashboard-succeeded.png)

![Event-Compensation-Dashboard-Error](/images/compensation/dashboard-error.png)

## 管理端点

Dashboard 的生成客户端当前使用空 `basePath`，默认命令路由为：

| 操作 | 路由 |
| --- | --- |
| 普通准备 | `PUT /execution_failed/{id}/prepare_compensation` |
| 强制准备 | `PUT /execution_failed/{id}/force_prepare_compensation` |
| 修改重试规格 | `PUT /execution_failed/{id}/apply_retry_spec` |
| 修改恢复性 | `PUT /execution_failed/{id}/mark_recoverable` |
| 修改目标函数 | `PUT /execution_failed/{id}/change_function` |

API Gateway 可以在外部添加 context 前缀；运行实例的 OpenAPI 是最终路由证据。生成客户端还包含默认聚合删除与恢复路由，但当前 Dashboard 不调用它们。

对一个已存在且可重试的失败记录执行普通准备：

```bash
curl -X PUT \
  'http://127.0.0.1:18083/execution_failed/<execution-id>/prepare_compensation' \
  -H 'Command-Wait-Stage: PROCESSED' \
  -H 'Command-Request-Id: prepare-<execution-id>'
```

`succeeded=true`、`stage=PROCESSED` 只证明 prepare 命令已处理。随后读取可能仍看到旧 `FAILED`、短暂 `PREPARED`，或已经看到最终 `SUCCEEDED` / 新的 `FAILED`。若要观察完整结果，应轮询 snapshot/event 查询并核对状态事件历史，而不是对一次即时读取断言。

失败路径也要验证：普通 prepare 拒绝 `SUCCEEDED`、未超时的 `PREPARED` 和达到上限的记录；force prepare 仍拒绝成功或未超时状态；对非 `PREPARED` 直接 apply success/failure 会返回 `ExecutionFailed is not prepared.`。Dashboard 按钮只是操作提示，服务端状态机才是最终决定。

## 通知验证

配置企业微信后，以受控失败和成功事件分别验证机器人消息、快速导航链接与敏感信息边界。WebHook 发送成功只证明通知可达，仍需在 Dashboard 或查询结果中核对权威状态。

| 失败通知 | 成功通知 |
| --- | --- |
| ![执行失败](/images/compensation/execution-failed.png) | ![执行成功](/images/compensation/execution-success.png) |

## 持久化部署与验证

持久化环境继续使用 distribution 的直接 `java` 启动路径，配置真实 MongoDB、Redis、Kafka、scheduler 与通知，然后移除本地示例中的 in-memory / disable 覆盖。仓库提供服务宿主和 Dashboard 构建，不提供可直接投产的集群策略。

最小 Kubernetes 形状如下；镜像摘要、资源、副本与 Secret 名称必须由实际发布和容量验证决定：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: compensation-service
spec:
  replicas: 2
  selector:
    matchLabels:
      app: compensation-service
  template:
    metadata:
      labels:
        app: compensation-service
    spec:
      containers:
        - name: compensation-service
          image: <registry>/wow-compensation-server@sha256:<digest>
          envFrom:
            - secretRef:
                name: wow-compensation-secrets
          ports:
            - name: http
              containerPort: 8080
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: http
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: http
```

部署验证至少包括：

1. 固定选定 Wow tag 构建的不可变镜像摘要，并在测试与生产使用同一摘要；
2. 通过 Secret 注入消息、存储、通知与认证凭据；
3. 验证 EventStore 与 SnapshotStore 的索引、容量、备份和恢复；
4. 验证 readiness/liveness、scheduler 互斥、积压、失败年龄、重启数与错误日志；
5. 将 Dashboard 与管理端点限制在受保护的运营网络，启用 TLS、认证、细粒度授权和审计；
6. 在测试环境走通正常、可重试、不可恢复、幂等和人工恢复，再推广同一镜像。

`replicas: 2` 本身不证明高可用；多个副本还依赖消息、存储和 scheduler 互斥的真实故障验证。

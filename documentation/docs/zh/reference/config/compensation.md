---
title: 事件补偿配置
description: 事件补偿的配置选项，包括 Starter 开关以及补偿服务端的重试、调度和 Webhook 设置。
---

# 事件补偿配置

补偿配置分为两层：

- **Starter 级**（`wow-spring-boot-starter`）—— 单个 `wow.compensation.enabled` 开关。
- **服务端级**（`wow-compensation-server`）—— 重试策略、调度器和 Webhook 投递。

## Starter 级

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `wow.compensation.enabled` | Boolean | `true` | 启用事件补偿功能 |

```yaml
wow:
  compensation:
    enabled: true
```

## 服务端级

以下属性用于配置独立的补偿服务端（`wow-compensation-server`）。

### 重试策略

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `wow.compensation.host` | String | _（空）_ | 补偿服务端的宿主地址 |
| `wow.compensation.max-retries` | Integer | `10` | 失败执行的最大重试次数 |
| `wow.compensation.min-backoff` | Integer | `180` | 重试之间的最小退避时间（秒） |
| `wow.compensation.execution-timeout` | Integer | `120` | 单次执行超时时间（秒） |

```yaml
wow:
  compensation:
    host: http://compensation-service
    max-retries: 10
    min-backoff: 180
    execution-timeout: 120
```

### 调度器

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `wow.compensation.scheduler.enabled` | Boolean | `true` | 启用定时重试执行循环 |
| `wow.compensation.scheduler.mutex` | String | `compensation_mutex` | 守护单实例调度的分布式锁 Key |
| `wow.compensation.scheduler.batch-size` | Integer | `100` | 每个调度周期处理的失败执行数量 |
| `wow.compensation.scheduler.initial-delay` | Duration | `PT60S`（60 秒） | 首次调度前的延迟 |
| `wow.compensation.scheduler.period` | Duration | `PT60S`（60 秒） | 调度周期之间的间隔 |

```yaml
wow:
  compensation:
    scheduler:
      enabled: true
      mutex: compensation_mutex
      batch-size: 100
      initial-delay: PT60S
      period: PT60S
```

### 企业微信 Webhook（可选）

将补偿事件投递到企业微信群机器人。

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `wow.compensation.webhook.weixin.url` | String | _（必填）_ | 企业微信群机器人 Webhook URL |
| `wow.compensation.webhook.weixin.events` | Set&lt;HookEvent&gt; | `execution_failed_created`、`execution_failed_applied`、`execution_success_applied`、`recoverable_marked` | 触发通知的 Hook 事件。可选值：`execution_failed_created`、`execution_failed_applied`、`execution_success_applied`、`compensation_prepared`、`recoverable_marked` |

```yaml
wow:
  compensation:
    webhook:
      weixin:
        url: https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=YOUR_KEY
        events:
          - execution_failed_created
          - execution_failed_applied
          - execution_success_applied
          - recoverable_marked
```

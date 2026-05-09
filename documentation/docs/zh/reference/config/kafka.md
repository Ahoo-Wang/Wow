---
title: Kafka 配置
description: Apache Kafka 集成的配置选项。
---

# Kafka 配置

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `wow.kafka.enabled` | Boolean | `true` | 启用 Kafka 集成 |
| `wow.kafka.bootstrap-servers` | List\<String\> | （必填） | Kafka 引导服务器地址 |
| `wow.kafka.topic-prefix` | String | `wow.` | Topic 名称前缀 |
| `wow.kafka.properties` | Map\<String, String\> | `{}` | 额外的 Kafka 客户端属性 |
| `wow.kafka.producer` | Map\<String, String\> | `{}` | Kafka 生产者属性 |
| `wow.kafka.consumer` | Map\<String, String\> | `{}` | Kafka 消费者属性 |

## 示例

```yaml
wow:
  kafka:
    enabled: true
    bootstrap-servers:
      - localhost:9092
    topic-prefix: "wow."
    producer:
      acks: all
      retries: 3
    consumer:
      auto-offset-reset: earliest
      group-id: wow-consumer
```

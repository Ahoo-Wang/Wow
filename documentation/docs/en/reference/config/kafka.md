---
title: Kafka Configuration
description: Configuration options for Apache Kafka integration.
---

# Kafka Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.kafka.enabled` | Boolean | `true` | Enable Kafka integration |
| `wow.kafka.bootstrap-servers` | List\<String\> | (required) | Kafka bootstrap server addresses |
| `wow.kafka.topic-prefix` | String | `wow.` | Topic name prefix |
| `wow.kafka.properties` | Map\<String, String\> | `{}` | Additional Kafka client properties |
| `wow.kafka.producer` | Map\<String, String\> | `{}` | Kafka producer-specific properties |
| `wow.kafka.consumer` | Map\<String, String\> | `{}` | Kafka consumer-specific properties |

## Example

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

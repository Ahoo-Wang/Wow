---
title: Redis Configuration
description: Configuration options for Redis event store and snapshot storage.
---

# Redis Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.redis.enabled` | Boolean | `true` | Enable Redis integration |

Redis connection is configured through Spring Boot's standard `spring.data.redis.*` properties.

## Example

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379

wow:
  redis:
    enabled: true
```

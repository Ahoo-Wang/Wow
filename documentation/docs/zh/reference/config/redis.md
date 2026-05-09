---
title: Redis 配置
description: Redis 事件存储和快照存储的配置选项。
---

# Redis 配置

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `wow.redis.enabled` | Boolean | `true` | 启用 Redis 集成 |

Redis 连接通过 Spring Boot 标准的 `spring.data.redis.*` 属性配置。

## 示例

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

---
title: Elasticsearch 配置
description: Elasticsearch 事件存储和投影的配置选项。
---

# Elasticsearch 配置

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `wow.elasticsearch.enabled` | Boolean | `true` | 启用 Elasticsearch 集成 |
| `wow.elasticsearch.auto-init-template` | Boolean | `true` | 启动时自动初始化索引模板 |

Elasticsearch 连接通过 Spring Boot 标准的 `spring.elasticsearch.*` 属性配置。

## 示例

```yaml
spring:
  elasticsearch:
    uris: http://localhost:9200

wow:
  elasticsearch:
    enabled: true
    auto-init-template: true
```

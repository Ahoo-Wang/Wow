---
title: Elasticsearch Configuration
description: Configuration options for Elasticsearch event store and projection.
---

# Elasticsearch Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.elasticsearch.enabled` | Boolean | `true` | Enable Elasticsearch integration |
| `wow.elasticsearch.auto-init-template` | Boolean | `true` | Automatically initialize index templates on startup |

Elasticsearch connection is configured through Spring Boot's standard `spring.elasticsearch.*` properties.

## Example

```yaml
spring:
  elasticsearch:
    uris: http://localhost:9200

wow:
  elasticsearch:
    enabled: true
    auto-init-template: true
```

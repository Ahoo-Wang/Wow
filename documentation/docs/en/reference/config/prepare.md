---
title: Prepare Key Configuration
description: Configuration options for application-level key uniqueness in EventSourcing.
---

# Prepare Key Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.prepare.enabled` | Boolean | `true` | Enable PrepareKey functionality |
| `wow.prepare.storage` | PrepareStorage | `MONGO` | Storage backend for PrepareKey |
| `wow.prepare.base-packages` | List\<String\> | `[]` | Base packages to scan for PrepareKey definitions |

## PrepareStorage Values

| Value | Description |
|-------|-------------|
| `MONGO` | MongoDB (recommended) |
| `REDIS` | Redis |

## Example

```yaml
wow:
  prepare:
    enabled: true
    storage: mongo
    base-packages:
      - com.example.domain
```

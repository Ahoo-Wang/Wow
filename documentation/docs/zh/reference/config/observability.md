---
title: Observability Configuration
description: Configuration options for OpenAPI spec generation and observability integrations.
---

# Observability Configuration

## OpenAPI

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.openapi.enabled` | Boolean | `true` | Enable OpenAPI spec generation |

```yaml
wow:
  openapi:
    enabled: true
```

When enabled, Wow automatically generates OpenAPI specifications from command and event models at compile time via the `wow-compiler` module.

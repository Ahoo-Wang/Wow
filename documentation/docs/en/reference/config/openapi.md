---
title: OpenAPI Configuration
description: Configuration options for OpenAPI spec generation and exposure.
---

# OpenAPI Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.openapi.enabled` | Boolean | `true` | Enable OpenAPI spec generation |

## Example

```yaml
wow:
  openapi:
    enabled: true
```

When enabled, Wow automatically generates OpenAPI specifications from command and event models at compile time via the `wow-compiler` module.

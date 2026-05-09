---
title: WebFlux Configuration
description: Configuration options for Spring WebFlux command endpoint integration.
---

# WebFlux Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.webflux.enabled` | Boolean | `true` | Enable WebFlux command endpoint auto-registration |
| `wow.webflux.global-error.enabled` | Boolean | `true` | Enable global error handling |

## Example

```yaml
wow:
  webflux:
    enabled: true
    global-error:
      enabled: true
```

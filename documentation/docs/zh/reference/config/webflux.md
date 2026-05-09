---
title: WebFlux 配置
description: Spring WebFlux 命令端点集成的配置选项。
---

# WebFlux 配置

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `wow.webflux.enabled` | Boolean | `true` | 启用 WebFlux 命令端点自动注册 |
| `wow.webflux.global-error.enabled` | Boolean | `true` | 启用全局错误处理 |

## 示例

```yaml
wow:
  webflux:
    enabled: true
    global-error:
      enabled: true
```

---
title: 可观测性配置
description: OpenAPI 规范生成和可观测性集成的配置选项。
---

# 可观测性配置

## OpenAPI

| 属性 | 类型 | 默认值 | 描述 |
|----------|------|---------|-------------|
| `wow.openapi.enabled` | Boolean | `true` | 启用 OpenAPI 规范生成 |

```yaml
wow:
  openapi:
    enabled: true
```

启用后，Wow 会在编译期通过 `wow-compiler` 模块从命令和事件模型自动生成 OpenAPI 规范。

---
title: OpenAPI 配置
description: OpenAPI 规范生成和暴露的配置选项。
---

# OpenAPI 配置

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `wow.openapi.enabled` | Boolean | `true` | 启用 OpenAPI 规范生成 |

## 示例

```yaml
wow:
  openapi:
    enabled: true
```

启用后，Wow 通过 `wow-compiler` 模块在编译时从命令和事件模型自动生成 OpenAPI 规范。

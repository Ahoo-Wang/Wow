---
title: 预分配 Key 配置
description: 事件溯源架构中应用级别 Key 唯一性的配置选项。
---

# 预分配 Key 配置

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `wow.prepare.enabled` | Boolean | `true` | 启用 PrepareKey 功能 |
| `wow.prepare.storage` | PrepareStorage | `MONGO` | PrepareKey 存储后端 |
| `wow.prepare.base-packages` | List\<String\> | `[]` | 扫描 PrepareKey 定义的基包路径 |

## PrepareStorage 可选值

| 值 | 说明 |
|----|------|
| `MONGO` | MongoDB（推荐） |
| `REDIS` | Redis |

## 示例

```yaml
wow:
  prepare:
    enabled: true
    storage: mongo
    base-packages:
      - com.example.domain
```

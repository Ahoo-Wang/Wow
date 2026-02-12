# 事件总线

- 配置类：[EventProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/event/EventProperties.kt)
- 前缀：`wow.event.`

| 名称            | 数据类型                    | 说明                                     | 默认值 |
|---------------|-------------------------|----------------------------------------|-----|
| `bus`         | `BusProperties`         | [BusProperties](./basic#busproperties) |     |

**YAML 配置样例**

```yaml
wow:
  event:
    bus:
      type: kafka
      local-first:
        enabled: true
```
# 基础

:::tip
当前配置文档仅涵盖规范级别的公共配置，有关各模块的具体配置，请查阅相应模块的配置文档。
:::

## WowProperties

- [WowProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/WowProperties.kt)
- 前缀：`wow.`

| 名称             | 数据类型      | 说明      | 默认值                          |
|----------------|-----------|---------|------------------------------|
| `enabled`      | `Boolean` | 是否启用    | `true`                       |
| `context-name` | `String`  | 限界上下文名称 | `${spring.application.name}` |

## BusProperties

> `BusProperties` 是 `CommandBus` 和 `EventBus` 的公共配置。

- [BusProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/BusProperties.kt)

| 名称            | 数据类型                   | 说明           | 默认值     |
|---------------|------------------------|--------------|---------|
| `type`        | `BusType`              | 消息总线类型       | `kafka` |
| `local-first` | `LocalFirstProperties` | LocalFist 模式 |         |

### BusType

```kotlin
enum class BusType {
    KAFKA,
    REDIS,
    IN_MEMORY,
    NO_OP;
}
```

### LocalFirstProperties

| 名称        | 数据类型      | 说明   | 默认值    |
|-----------|-----------|------|--------|
| `enabled` | `Boolean` | 是否启用 | `true` |
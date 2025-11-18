# Basic

:::tip
The current configuration documentation only covers specification-level public configurations. For specific configurations of each module, please refer to the configuration documentation of the respective module.
:::

## WowProperties

- [WowProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/WowProperties.kt)
- Prefix: `wow.`

| Name            | Data Type  | Description | Default Value                  |
|----------------|------------|-------------|-------------------------------|
| `enabled`      | `Boolean` | Whether to enable | `true`                       |
| `context-name` | `String`  | Bounded context name | `${spring.application.name}` |

## BusProperties

> `BusProperties` is the common configuration for `CommandBus` and `EventBus`.

- [BusProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/BusProperties.kt)

| Name           | Data Type               | Description | Default Value |
|---------------|------------------------|-------------|---------------|
| `type`        | `BusType`              | Message bus type | `kafka` |
| `local-first` | `LocalFirstProperties` | LocalFirst mode |  |

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

| Name       | Data Type  | Description | Default Value |
|-----------|------------|-------------|---------------|
| `enabled` | `Boolean` | Whether to enable | `true` |
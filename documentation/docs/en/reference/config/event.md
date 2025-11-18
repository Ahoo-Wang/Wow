# Event Bus

- Configuration class: [EventProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/event/EventProperties.kt)
- Prefix: `wow.event.`

| Name           | Data Type               | Description | Default Value |
|---------------|------------------------|-------------|---------------|
| `bus`         | `BusProperties`         | [BusProperties](./basic#busproperties) |  |

**YAML Configuration Example**

```yaml
wow:
  event:
    bus:
      type: kafka
      local-first:
        enabled: true
```
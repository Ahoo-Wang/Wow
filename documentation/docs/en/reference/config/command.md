# Command Bus

- Configuration class: [CommandProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/command/CommandProperties.kt)
- Prefix: `wow.command.`

| Name           | Data Type               | Description | Default Value |
|---------------|------------------------|-------------|---------------|
| `bus`         | `BusProperties`         | [BusProperties](./basic#busproperties) |  |
| `idempotency` | `IdempotencyProperties` | Command idempotency |  |

**YAML Configuration Example**

```yaml
wow:
  command:
    bus:
      type: kafka
      local-first:
        enabled: true
    idempotency:
      enabled: true
      bloom-filter:
        expected-insertions: 1000000
        ttl: PT60S
        fpp: 0.00001
```

## IdempotencyProperties

- Configuration class: [IdempotencyProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/command/CommandProperties.kt)

| Name            | Data Type     | Description | Default Value |
|----------------|---------------|-------------|---------------|
| `enabled`      | `boolean`     | Whether to enable | `true` |
| `bloom-filter` | `BloomFilter` | BloomFilter |  |

### BloomFilter

| Name                   | Data Type    | Description | Default Value              |
|-----------------------|--------------|-------------|----------------------------|
| `ttl`                 | `Duration`   | Time to live | `Duration.ofMinutes(1)` |
| `expected-insertions` | `Long`       |  | `1000_000`              |
| `fpp`                 | `Double`     |  | `0.00001`               |

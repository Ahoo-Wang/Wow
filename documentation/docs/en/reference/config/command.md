# 命令总线

- 配置类：[CommandProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/command/CommandProperties.kt)
- 前缀：`wow.command.`

| 名称            | 数据类型                    | 说明                                     | 默认值 |
|---------------|-------------------------|----------------------------------------|-----|
| `bus`         | `BusProperties`         | [BusProperties](./basic#busproperties) |     |
| `idempotency` | `IdempotencyProperties` | 命令幂等性                                  |     |

**YAML 配置样例**

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

- 配置类：[IdempotencyProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/command/CommandProperties.kt)

| 名称             | 数据类型          | 说明          | 默认值    |
|----------------|---------------|-------------|--------|
| `enabled`      | `boolean`     | 是否启用        | `true` |
| `bloom-filter` | `BloomFilter` | BloomFilter |        |

### BloomFilter

| 名称                    | 数据类型       | 说明   | 默认值                     |
|-----------------------|------------|------|-------------------------|
| `ttl`                 | `Duration` | 存活时间 | `Duration.ofMinutes(1)` |
| `expected-insertions` | `Long`     |      | `1000_000`              |
| `fpp`                 | `Double`   |      | `0.00001`               |

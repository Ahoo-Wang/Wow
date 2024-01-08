# Kafka

_Kafka_ 扩展提供了对 Kafka 的支持，实现了 `CommandBus`、`DomainEventBus` 和 `StateEventBus`。

## 安装

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-kafka")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-kafka'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-kafka</artifactId>
    <version>${wow.version}</version>
</dependency>
```
:::

## 配置

- 配置类：[KafkaProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/kafka/KafkaProperties.kt)
- 前缀：`wow.kafka.`

| 名称                  | 数据类型                  | 说明          | 默认值    |
|---------------------|-----------------------|-------------|--------|
| `enabled`           | `Boolean`             | 是否启用        | `true` |
| `bootstrap-servers` | `List<String>`        | Kafka 服务器地址 |        |
| `topic-prefix`      | `String`              | 主题前缀        | `wow.` |
| `properties`        | `Map<String, String>` | 公共配置        |        |
| `producer`          | `Map<String, String>` | 生产者配置       |        |
| `consumer`          | `Map<String, String>` | 消费者配置       |        |

**YAML 配置样例**

```yaml
wow:
  command:
    bus:
      type: kafka
  kafka:
    bootstrap-servers: localhost:61530
    topic-prefix: 'wow.'
```
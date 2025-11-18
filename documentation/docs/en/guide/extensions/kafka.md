# Kafka

The _Kafka_ extension provides support for Kafka, implementing `CommandBus`, `DomainEventBus`, and `StateEventBus`.

## Installation

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

## Configuration

- Configuration class: [KafkaProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/kafka/KafkaProperties.kt)
- Prefix: `wow.kafka.`

| Name                  | Data Type               | Description | Default Value |
|---------------------|-----------------------|-------------|---------------|
| `enabled`           | `Boolean`             | Whether to enable | `true` |
| `bootstrap-servers` | `List<String>`        | Kafka server addresses |  |
| `topic-prefix`      | `String`              | Topic prefix | `wow.` |
| `properties`        | `Map<String, String>` | Common configuration |  |
| `producer`          | `Map<String, String>` | Producer configuration |  |
| `consumer`          | `Map<String, String>` | Consumer configuration |  |

**YAML Configuration Example**

```yaml
wow:
  command:
    bus:
      type: kafka
  kafka:
    bootstrap-servers: localhost:61530
    topic-prefix: 'wow.'
```
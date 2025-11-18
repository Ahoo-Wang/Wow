# Mongo

The _Mongo_ extension provides support for MongoDB, implementing the following interfaces:

- `EventStore`
- `EventStreamQueryService`
- `SnapshotRepository`
- `SnapshotQueryService`
- `PrepareKey`

## Installation

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-mongo")
implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-mongo'
implementation 'org.springframework.boot:spring-boot-starter-data-mongodb-reactive'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-mongo</artifactId>
    <version>${wow.version}</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb-reactive</artifactId>
</dependency>
```
:::

## Configuration

- Configuration class: [MongoProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoProperties.kt)
- Prefix: `wow.mongo.`

| Name                      | Data Type  | Description | Default Value |
|-------------------------|------------|-------------|---------------|
| `enabled`               | `Boolean` | Whether to enable | `true` |
| `auto-init-schema`      | `Boolean` | Whether to auto-generate *Schema* | `true` |
| `event-stream-database` | `String`  | Event stream database name | Database name configured by Spring Boot Mongo module |
| `snapshot-database`     | `String`  | Snapshot database name | Database name configured by Spring Boot Mongo module |
| `prepare-database`      | `String`  | `PrepareKey` database name | Database name configured by Spring Boot Mongo module |

**YAML Configuration Example**

```yaml
wow:
  mongo:
    enabled: true
    auto-init-schema: true
    event-stream-database:
    snapshot-database:
    prepare-database:
```
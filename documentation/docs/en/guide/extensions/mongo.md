# Mongo

_Mongo_ 扩展提供了对 MongoDB 的支持，实现了以下接口：

- `EventStore`
- `EventStreamQueryService`
- `SnapshotRepository`
- `SnapshotQueryService`
- `PrepareKey`

## 安装

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

## 配置

- 配置类：[MongoProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoProperties.kt)
- 前缀：`wow.mongo.`

| 名称                      | 数据类型      | 说明                 | 默认值                          |
|-------------------------|-----------|--------------------|------------------------------|
| `enabled`               | `Boolean` | 是否启用               | `true`                       |
| `auto-init-schema`      | `Boolean` | 是否自动生成 *Schema*    | `true`                       |
| `event-stream-database` | `String`  | 事件流数据库名称           | Spring Boot Mongo 模块配置的数据库名称 |
| `snapshot-database`     | `String`  | 快照数据库名称            | Spring Boot Mongo 模块配置的数据库名称 |
| `prepare-database`      | `String`  | `PrepareKey` 数据库名称 | Spring Boot Mongo 模块配置的数据库名称 |

**YAML 配置样例**

```yaml
wow:
  mongo:
    enabled: true
    auto-init-schema: true
    event-stream-database:
    snapshot-database:
    prepare-database: 
```
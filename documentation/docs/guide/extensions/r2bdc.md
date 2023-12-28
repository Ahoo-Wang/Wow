# R2DBC

_R2DBC_ 扩展提供了使用响应式编程的方式对关系型数据库的支持，实现了 `EventStore` 和 `SnapshotRepository` 。
使开发者能够直接利用关系型数据库进行事件存储和快照存储。
同时提供了简单模式，跟分片模式的支持。

## 安装

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-r2dbc")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-r2dbc'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-r2dbc</artifactId>
    <version>${wow.version}</version>
</dependency>
```
:::

## 配置

- 配置类：[R2dbcProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/r2dbc/R2dbcProperties.kt)
- 前缀：`wow.r2dbc.`

| 名称                      | 数据类型      | 说明                 | 默认值                          |
|-------------------------|-----------|--------------------|------------------------------|
| `enabled`               | `Boolean` | 是否启用               | `true`                       |

**YAML 配置样例**

```yaml
wow:
  r2dbc:
    enabled: true
```

## DataSourceProperties

- 配置类：[DataSourceProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/r2dbc/DataSourceProperties.kt)
- 前缀：`wow.r2dbc.datasource.`

| 名称     | 数据类型   | 说明         | 默认值      |
|--------|--------|------------|----------|
| `type` | `Type` | 模式：简单/分片模式 | `simple` |

### Type

```kotlin
enum class Type {
    SIMPLE,
    SHARDING
    ;
}
```

## ShardingProperties

- 配置类：[ShardingProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/r2dbc/ShardingProperties.kt)
- 前缀：`wow.r2dbc.datasource.sharding`

| 名称             | 数据类型                             | 说明      | 默认值 |
|----------------|----------------------------------|---------|-----|
| `databases`    | `Map<String, Database>`          | 分片数据库   |     |
| `event-stream` | `Map<String, ShardingRule>`      | 事件流分片规则 |     |
| `snapshot`     | `Map<String, ShardingRule>`      | 快照分片规则  |     |
| `algorithms`   | `Map<String, ShardingAlgorithm>` | 分片算法    |     |

### Database

| 名称    | 数据类型     | 说明      | 默认值 |
|-------|----------|---------|-----|
| `url` | `String` | 数据库连接地址 |     |


### ShardingRule

| 名称                   | 数据类型     | 说明      | 默认值 |
|----------------------|----------|---------|-----|
| `database-algorithm` | `String` | 数据库分片算法 |     |
| `table-algorithm`    | `String` | 表分片算法   |     |

### ShardingAlgorithm

| 名称     | 数据类型           | 说明       | 默认值   |
|--------|----------------|----------|-------|
| `type` | `String`       | 分片算法类型   | `mod` |
| `mod`  | `ModAlgorithm` | 取模分片算法配置 |       |

#### ModAlgorithm

| 名称                  | 数据类型     | 说明    | 默认值 |
|---------------------|----------|-------|-----|
| `logic-name-prefix` | `String` | 逻辑名前缀 |     |
| `divisor`           | `Int`    | 除数    |     |
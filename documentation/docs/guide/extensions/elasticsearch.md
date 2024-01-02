# Elasticsearch

_Elasticsearch_ 扩展提供了对 _Elasticsearch_ 的支持，实现了  `SnapshotRepository`.

## 安装

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-elasticsearch")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-elasticsearch'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-elasticsearch</artifactId>
    <version>${wow.version}</version>
</dependency>
```
:::

## 配置

**YAML 配置样例**

```yaml
wow:
  eventsourcing:
    snapshot:
      storage: elasticsearch
```
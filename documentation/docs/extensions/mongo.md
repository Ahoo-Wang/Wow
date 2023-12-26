# Mongo

## 安装

<CodeGroup>
  <CodeGroupItem title="Gradle(Kotlin)" active>

```kotlin
implementation("me.ahoo.wow:wow-mongo")
implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
```

  </CodeGroupItem>
  <CodeGroupItem title="Gradle(Groovy)">

```groovy
implementation 'me.ahoo.wow:wow-mongo'
implementation 'org.springframework.boot:spring-boot-starter-data-mongodb-reactive'
```

  </CodeGroupItem>
  <CodeGroupItem title="Maven">

```xml

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
  </CodeGroupItem>
</CodeGroup>
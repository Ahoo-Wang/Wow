---
name: b08-migrate-audit
tags: [behavior, migration, read-only, synthetic-fixture]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

只读审计这个 Wow v6.21.5 synthetic service 升级到固定 release v8.9.6 的风险。核对 Java 版本、现有 store/snapshot 配置与示例聚合源码；明确哪些配置无需重命名，以及该 fixture 不能证明什么。

Files from the user's project, shown inline:

`README.md`:

```markdown
# Synthetic v6.21.5 service

This small non-production Gradle project contains a pinned framework starter,
one representative aggregate, Java 17 toolchain configuration, and local
store/snapshot property declarations. It has no Gradle wrapper, generated
artifacts, deployment manifests, external-service setup, or application data.
```

`build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.4.10"
    id("org.springframework.boot") version "4.0.6"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("me.ahoo.wow:wow-spring-boot-starter:6.21.5")
}

kotlin {
    jvmToolchain(17)
}
```

`settings.gradle.kts`:

```kotlin
rootProject.name = "wow-v6-fixture"
```

`src/main/kotlin/example/LegacyOrder.kt`:

```kotlin
package example

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.annotation.OnSourcing

data class CreateOrder(val orderId: String)
data class OrderCreated(val orderId: String)

@AggregateRoot
class LegacyOrder(private val state: LegacyOrderState) {
    @OnCommand
    fun create(command: CreateOrder): OrderCreated = OrderCreated(command.orderId)
}

class LegacyOrderState {
    var created: Boolean = false
        private set

    @OnSourcing
    fun onCreated(event: OrderCreated) {
        created = true
    }
}
```

`src/main/resources/application.yml`:

```yaml
wow:
  eventsourcing:
    store:
      storage: mongo
    snapshot:
      storage: mongo

spring:
  data:
    mongodb:
      database: wow-v6-fixture
```

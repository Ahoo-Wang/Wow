---
name: b09-migrate-local-platform
tags: [behavior, migration, local-platform, mutating, synthetic-fixture]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

仅在 fixture 内把平台依赖从 Wow v6.21.5 迁移到已发布的 Wow v8.9.6、Spring Boot 4.1.0 和 Kotlin 2.4.10，保持 Java 17 与现有 store/snapshot 配置不变；先记录变更前证据，再修改并检查精确 diff。给出所有尚未授权、尚未验证的生产数据切换步骤。

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

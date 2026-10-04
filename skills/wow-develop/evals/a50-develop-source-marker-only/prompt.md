---
name: a50-develop-source-marker-only
tags: [activation, trigger, source-marker, fixture, no-wow-keyword]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

为这个服务的 Cart 聚合增加容量不变量，并补齐 command、event、sourcing 和行为测试。

Files from the user's project, shown inline:

`build.gradle.kts`:

```kotlin
dependencies {
    implementation("me.ahoo.wow:wow-spring-boot-starter:8.16.3")
}
```

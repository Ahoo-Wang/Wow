---
name: a63-none-implicit-framework-repository
tags: [activation, negative, wow-framework-repository, implicit-repository, fixture, development]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

为 :wow-core 的 CommandGateway 增加一个公共 API，并补齐当前模块测试。

Files from the user's project, shown inline:

`settings.gradle.kts`:

```kotlin
rootProject.name = "Wow"
include(":wow-api", ":wow-core")
```

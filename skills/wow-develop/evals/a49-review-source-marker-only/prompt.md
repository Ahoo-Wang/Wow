---
name: a49-review-source-marker-only
tags: [activation, trigger, source-marker, fixture, no-wow-keyword]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

审查这个下游服务的聚合补丁，按严重度报告 findings，不要修改文件。

Files from the user's project, shown inline:

`build.gradle.kts`:

```kotlin
dependencies {
    implementation("me.ahoo.wow:wow-spring-boot-starter:8.16.3")
}
```

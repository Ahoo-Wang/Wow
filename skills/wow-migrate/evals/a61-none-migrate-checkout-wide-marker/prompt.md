---
name: a61-none-migrate-checkout-wide-marker
tags: [activation, negative, checkout-wide-marker, non-wow-scope, fixture, migration]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

把 services/plain-service 的 Flyway 报表表迁移到新 schema，要求对账和回滚。

Files from the user's project, shown inline:

`non-wow-scope.patch`:

```diff
diff --git a/services/plain-service/build.gradle.kts b/services/plain-service/build.gradle.kts
new file mode 100644
--- /dev/null
+++ b/services/plain-service/build.gradle.kts
@@ -0,0 +1 @@
+plugins { kotlin("jvm") }
diff --git a/services/wow-service/build.gradle.kts b/services/wow-service/build.gradle.kts
new file mode 100644
--- /dev/null
+++ b/services/wow-service/build.gradle.kts
@@ -0,0 +1 @@
+dependencies { implementation("me.ahoo.wow:wow-spring-boot-starter:8.16.3") }
```

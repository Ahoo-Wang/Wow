---
name: a59-none-debug-checkout-wide-marker
tags: [activation, negative, checkout-wide-marker, non-wow-scope, fixture, debug]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

services/plain-service 的请求偶发超时；定位根因，不要修改文件。

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

---
name: b07-debug-fix
tags: [behavior, diagnose-fix, mutating, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

定位并修复这个下游服务中 OrderSaga 直接测试通过但运行时未注册的问题；先保留失败证据，再做最小修复并复验。

Files from the user's project, shown inline:

`B06.patch`:

```diff
diff --git a/src/main/kotlin/example/order/OrderSaga.kt b/src/main/kotlin/example/order/OrderSaga.kt
--- a/src/main/kotlin/example/order/OrderSaga.kt
+++ b/src/main/kotlin/example/order/OrderSaga.kt
@@ -16,2 +16 @@
-import me.ahoo.wow.api.annotation.StatelessSaga
 import example.api.order.OrderCreated
@@ -21,2 +20 @@
-@StatelessSaga
 class OrderSaga {
```

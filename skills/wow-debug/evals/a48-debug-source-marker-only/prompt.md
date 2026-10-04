---
name: a48-debug-source-marker-only
tags: [activation, trigger, source-marker, fixture, no-wow-keyword]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

这个 Saga 的直接 handler 测试通过，但运行时没有调用；只定位第一个失败阶段。

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

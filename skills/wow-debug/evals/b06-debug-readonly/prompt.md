---
name: b06-debug-readonly
tags: [behavior, diagnosis, read-only, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

下游订单服务中 OrderSaga 的直接 handler 测试通过，但运行时 handler 没有被调用；只定位原因，不要修改文件。

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

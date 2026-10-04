---
name: b05-review-fix-order
tags: [behavior, review-fix, test-first, mutating, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

审查并修复下游应用 diff 中的合并阻塞问题；先完成 findings，再补回归测试、修复并复审新 diff。

Files from the user's project, shown inline:

`B05.patch`:

```diff
diff --git a/src/main/kotlin/example/cart/Cart.kt b/src/main/kotlin/example/cart/Cart.kt
--- a/src/main/kotlin/example/cart/Cart.kt
+++ b/src/main/kotlin/example/cart/Cart.kt
@@ -41,7 +41,7 @@ class Cart(private val state: CartState) {
     fun onCommand(
         command: AddCartItem
     ): Any {
-        require(state.items.size < MAX_CART_ITEM_SIZE) {
+        require(state.items.size <= MAX_CART_ITEM_SIZE) {
             "购物车最多只能添加[$MAX_CART_ITEM_SIZE]个商品."
         }
         state.items.firstOrNull {
```

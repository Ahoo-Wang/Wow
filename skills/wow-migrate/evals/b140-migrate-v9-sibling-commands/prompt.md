---
name: b140-migrate-v9-sibling-commands
tags: [behavior, migration, read-only, v9-to-v10, source-rewrite, test-dsl, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

只读规划把下游服务购物车聚合的测试从 Wow 9.9.0 迁到 V10 的测试 DSL（源码目标为 Wow `main` 分支，尚未发布），给出改写后的测试代码，不改文件。领域代码已经迁好，生成的模型是 `cartModel(...)`。9.x 测试如下：

```kotlin
class CartSpec : AggregateSpec<Cart, CartState>({
    on {
        givenOwnerId("customer-1")
        givenEvent(CartItemAdded(productId = "apple", quantity = 1)) {
            whenCommand(RemoveCartItem(productIds = setOf("apple"))) {
                expectEventType(CartItemRemoved::class)
                expectState { items.assert().isEmpty() }
            }
            whenCommand(AddCartItem(productId = "apple", quantity = 2)) {
                expectEventType(CartQuantityChanged::class)
                expectState { items.single().quantity.assert().isEqualTo(3) }
            }
            whenCommand(AddCartItem(productId = "pear", quantity = 1)) {
                expectEventType(CartItemAdded::class)
                expectState { items.assert().hasSize(2) }
            }
        }
    }
})
```

同事打算逐行照搬：保留三个 `whenCommand` 的顺序，只把 `expectEventType` 改成 V10 的名字。在回答里给出代码。

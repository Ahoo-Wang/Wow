```kotlin
class CartAggregateSpec : AggregateSpec<Cart, CartState>(
    {
        given {
            val ownerId = generateGlobalId()
            val addCartItem = AddCartItem(
                productId = "productId",
                quantity = 1,
            )
            givenOwnerId(ownerId)
            whenCommand(addCartItem) {
                expectNoError()
                expectEventType(CartItemAdded::class)
                expectState {
                    items.assert().hasSize(1)
                }
                expectStateAggregate {
                    ownerId.assert().isEqualTo(ownerId)
                }
                fork {
                    val removeCartItem = RemoveCartItem(
                        productIds = setOf(addCartItem.productId),
                    )
                    whenCommand(removeCartItem) {
                        expectEventType(CartItemRemoved::class)
                    }
                }
                fork {
                    whenCommand(DefaultDeleteAggregate) {
                        expectEventType(DefaultAggregateDeleted::class)
                        expectStateAggregate {
                            deleted.assert().isTrue()
                        }

                        fork {
                            whenCommand(DefaultDeleteAggregate) {
                                expectErrorType(IllegalAccessDeletedAggregateException::class)
                            }
                        }
                        fork {
                            whenCommand(DefaultRecoverAggregate) {
                                expectNoError()
                                expectStateAggregate {
                                    deleted.assert().isFalse()
                                }
                                fork {
                                    whenCommand(DefaultRecoverAggregate) {
                                        expectErrorType(IllegalStateException::class)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
)
```
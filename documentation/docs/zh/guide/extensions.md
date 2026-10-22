---
title: Reactor、ID 与模型
description: 用 wow-reactor 继续以 Reactor 编写处理函数，用 wow-cosid 从 CosId 获取 ID，并复用 wow-models 的能力接口和树形聚合。
---

# Reactor、ID 与模型

三个可选模块扩展了领域能使用的东西。它们都不是必需的：不用它们的服务编写 `suspend` 函数，使用 UUIDv7 ID，并定义自己的模型。

## Reactor 处理函数：`wow-reactor` {#reactor-handlers-wow-reactor}

Wow 的 API 处处是协程，但 decide 函数、处理器的处理函数或它调用的服务，可以继续返回 Reactor 类型。返回 `Mono` 的处理函数产出它的值，或者什么都不产出；返回 `Flux` 或任何其他 `Publisher` 的处理函数，产出它发出的每个元素，作为事件或命令的列表：

<!-- sample: fragment -->

```kotlin
@OnCommand(produces = [OrderShipped::class])
fun onCommand(command: ShipOrder, carriers: CarrierService): Mono<OrderShipped> =
    carriers.book(command.carrier).map { booking -> OrderShipped(booking.trackingNumber) }
```

编译器生成通过 `wow-reactor` 等待 publisher 的代码；类路径上没有这个模块时，返回 Reactor 类型的处理函数无法编译。返回 publisher 的处理函数不标注 `@Blocking`：由 publisher 决定它的工作在哪里运行。调用 `suspend` 客户端 `CommandClient` 或 `QueryApi` 的 Reactor 代码，把每次调用包在 `kotlinx-coroutines-reactor` 的 `mono { … }` 中。

## ID：`wow-cosid` {#ids-wow-cosid}

节点生成两类 ID：全局 ID，即命令 ID、请求 ID、等待 ID 以及领域代码中的 `scope.ids.next()`；以及聚合类型的 ID，它分配给没有指明 ID 的创建命令，并由 `scope.ids.next(type)` 返回。默认情况下两者都是版本 7 的 UUID：按时间排序，无需协调即可唯一。

类路径上有 `wow-cosid`、应用中运行着 CosId 时，Spring Boot 宿主改为从 [CosId](https://github.com/Ahoo-Wang/CosId) 获取 ID：

- 全局 ID 来自名为 `wow.cosid.global-generator`（默认为 `cosid`）的 CosId 生成器，没有这个名字的生成器时，来自它的共享生成器；
- 聚合类型的 ID 来自以该聚合命名的 CosId 生成器，否则来自该类型自己的生成器，使用 CosId 分配的机器号；
- `wow.aggregates.[<context>.<aggregate>].id-generator` 为某个聚合类型指定任意 CosId 生成器。

自己定义了 `GlobalIdGenerator` 或 `wowAggregateIdGenerator` bean 的应用会保留它：模块会让步。在 decide 函数中获取的 ID，在版本冲突之后重新做决定时会再获取一次，这是安全的：冲突的那次尝试什么都没有追加，重试的请求由它首次的提交回答。

## 模型：`wow-models` {#models-wow-models}

`wow-models` 包含领域可以共享的模型辅助：能力接口（`NameCapable`、`TitleCapable`、`RemarkCapable`、`ReasonCapable`、`QuantityCapable`、`TypeCapable`、`StatusCapable` 等）、`EnumText`，以及带 `Trees.toTree` 的树形编码。它可复用的 `Tree` 聚合保存一个租户的编码树，带有 `Create`、`Update`、`Delete` 和 `Move` 命令。领域用自己的命令和事件继承它，编译器绑定它继承的处理函数；子类通过校验钩子加上自己的规则：

<!-- sample: extensions/models/src/test/kotlin/me/ahoo/wow/models/tree/aggregate/Category.kt#tree -->

```kotlin
/**
 * A tenant's category tree: every handler is the library's [Tree]'s, bound with the concrete commands. The verify
 * hooks add a rule of the domain, and one of the tree's codes is declared to the Manifest with its status.
 */
@AggregateRoot(ownership = Ownership.TENANT)
class Category(state: CategoryState) :
    Tree<CategoryState, CreateCategory, UpdateCategory, DeleteCategory, MoveCategory>(state) {
    override fun maxLevel(): Int = MAX_LEVEL

    override suspend fun verifyCreate(command: CreateCategory, scope: DecideScope): Rejection? = reserved(command.name)

    override suspend fun verifyUpdate(command: UpdateCategory, scope: DecideScope): Rejection? = reserved(command.name)

    private fun reserved(name: String): Rejection? =
        if (name.equals(RESERVED_NAME, ignoreCase = true)) {
            Rejection.of(NAME_RESERVED, "The category name [$name] is reserved.")
        } else {
            null
        }

    companion object {
        const val MAX_LEVEL = 3
        const val RESERVED_NAME = "uncategorized"

        @ErrorCode(description = "The category name is reserved.", status = 409)
        const val NAME_RESERVED = "category.name-reserved"

        /** [TreeRejections.PARENT_NOT_FOUND], declared to the Manifest with its status. */
        @ErrorCode(description = "The parent category does not exist.", status = 404)
        const val PARENT_NOT_FOUND = "tree.parent-not-found"
    }
}
```

为具体的创建命令标注 `@AllowCreate`：编译器读取的是命令自己的类。包仍是 `me.ahoo.wow.models.common` 和 `me.ahoo.wow.models.tree`，与 9.x 相同，所以迁移来的领域的导入保持不变。

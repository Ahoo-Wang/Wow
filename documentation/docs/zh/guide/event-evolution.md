---
title: 演进事件
description: 用修订号和升级函数改变事件，而不改写日志；重命名命令和事件、让事件退役，以及改变状态的结构。
outline: deep
---

# 演进事件

日志从不改写，所以今天写下的事件，在聚合存在的整个期间都会被读取。事件的形状必须改变时，它的类说明它现在是哪个修订号，再由一个小函数在读取时升级每条较旧的记录。本页说明哪些改变什么都不需要、哪些需要新的修订号，以及怎样重命名或退役一个事件。模型见[决定与演化](../concepts/programming-model#events-change-over-time)。

## 什么都不需要的改变 {#changes-that-need-nothing}

增加一个带默认值的可选字段，不需要新的修订号：没有这个字段的旧记录会以默认值读取。只增加读者可以忽略的内容的改变也一样。

其他所有改变（删除、重命名字段或改变字段类型、字段含义的变化、必填字段）都需要新的修订号。

## 提高修订号 {#raise-the-revision}

事件的类用 `@Revision(n)` 标明它当前的修订号；没有标注的事件是修订号 1。视图存储的 `ViewCreated` 在修订号 2 中增加了视图的 `kind`：

<!-- sample: platform/view-store/view-store-api/src/main/kotlin/me/ahoo/wow/viewstore/api/view/ViewEvents.kt#view-created -->

```kotlin
/** Revision 2 records the [kind]; a 9.x record (revision 1) has none, and is read with its config's `kind`. */
@Summary("A view was saved")
@Revision(2)
data class ViewCreated(
    val definitionId: String,
    val title: String,
    val kind: ViewKind,
    val audience: ViewAudience,
    val appId: String,
    val config: Map<String, Any?>,
)
```

## 升级较旧的记录 {#upgrade-the-older-records}

每个较旧的修订号都有一个升级函数，标注 `@Upgrade(event = X::class, from = n)`，把存储的修订号 `n` 的 JSON 变成修订号 `n + 1`。一个现在是修订号 3 的事件，其修订号 1 的记录会先经过从 1 开始的升级，再经过从 2 开始的升级：

<!-- sample: platform/view-store/view-store-domain/src/main/kotlin/me/ahoo/wow/viewstore/domain/view/ViewUpgrades.kt#upgrades -->

```kotlin
/**
 * The 9.x records of a view, read as revision 2 (decision D267): 9.x recorded no `kind`, which it defined as the
 * config's own `kind` (its `ViewKind`), so the upgrade takes it from there. A 9.x config without a kind, or with one
 * that is no [ViewKind], fails the read: the view is not guessed into a kind, and the read names the view.
 */
object ViewUpgrades {
    @Upgrade(event = ViewCreated::class, from = 1)
    @JvmStatic
    fun viewCreatedFrom1(node: ObjectNode): ObjectNode = withKind(node)

    @Upgrade(event = ViewSaved::class, from = 1)
    @JvmStatic
    fun viewSavedFrom1(node: ObjectNode): ObjectNode = withKind(node)

    private fun withKind(node: ObjectNode): ObjectNode {
        val named = node.get(CONFIG)?.get(ViewConfigs.KIND)?.takeIf { it.isString }?.stringValue()
        val kind = requireNotNull(ViewKind.of(named)) {
            "A 9.x view's config has no kind among ${ViewKind.entries.map { it.value }}: ${node.get(
                CONFIG
            )?.get(ViewConfigs.KIND)}."
        }
        return node.put(KIND, kind.value)
    }

    private const val CONFIG = "config"
    private const val KIND = "kind"
}
```

- 升级函数是顶层函数，或者是 `object` 的成员（带 `@JvmStatic`）。
- 它接收并返回 Jackson 3 的 `JsonNode` 或 `ObjectNode`（`tools.jackson.databind`）：它处理的是记录，而不是类，因为类已经与记录对不上了。
- 它是纯函数：没有 I/O，没有服务，也不挂起。它可以失败，就像视图的升级函数在读不了某条记录时那样；这时读取失败，并指出是哪条记录。
- 修订号为 `n` 的事件，从修订号 1 到 `n - 1` 每个都恰好需要一个升级函数。缺口或重复会让编译失败。

凡是读取日志的地方都会升级记录：加载聚合时，以及订阅读取它的事件时，所以处理函数看到的永远是当前的类。发布的事件流承载的事件也是当前修订号。

## 重命名命令或事件 {#rename-a-command-or-an-event}

命令或事件按它的名字存储和路由，名字是类的简单名，除非 `@Name` 指定了别的。要重命名类，又要继续读取以旧名字写下的内容，就用 `@Alias("OldName")` 列出旧名字。不带点的值是同一聚合中的旧名字；带点的值是完整类型名 `context.aggregate.Name`，用于从另一个聚合移过来的类。

## 让事件退役 {#retire-an-event}

聚合不再有的事件就让它退役：删除它的类和演化函数，并在 `@AggregateRoot(retiredEvents = [...])` 中列出它存储的名字。加载聚合时会跳过它的记录，但这些记录所在的流仍算作版本，订阅也会越过它们。名字既不是某个事件的、也没有退役的记录，读取时仍会失败。

## 改变状态的结构 {#change-the-state-s-structure}

快照按状态被序列化时的样子保存它。当存储的快照不再能读成状态，或者不再包含状态现在要推出的东西（某个演化函数的含义变了、字段被重命名或改了类型、历史必须计算出的新字段）时，提高 `@AggregateRoot(stateRevision = n)`。服务随后在后台原地从日志重建存储的快照。给状态增加一个可选字段不需要新的修订号。

## 对端也看得到 {#peers-see-it-too}

api 模块的事件和聚合的状态是上下文契约的一部分：对端从发布的事件流读取它们。Manifest 的兼容检查把每次构建与提交的基线比较，在同一主版本内，会让破坏读者的改变失败，例如删除字段或改变字段类型，无论修订号怎么说。这项检查见[一份契约](../concepts/contract#compatibility)。

## 测试 {#test-it}

`aggregateSpec` 中的 `givenRecords` 按日志保存的样子提交记录，所以测试可以从较旧修订号的记录加载聚合（经过它的升级函数），或者从退役事件的记录加载聚合，并检查它最终的状态：见[测试领域代码](./testing)。

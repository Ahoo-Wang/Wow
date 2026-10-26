---
title: 拒绝与错误码
description: 用声明的错误码拒绝命令，为它指定 HTTP 状态和结构化数据，并以调用方、对端、Saga 或测试的身份读取它。
outline: deep
---

# 拒绝与错误码

被聚合拒绝的命令是一种业务结果，而不是程序的错误。聚合返回一个带稳定错误码的拒绝，什么都不写，并告诉调用方原因。本页说明怎样声明错误码，以及每种调用方收到什么。

## 拒绝，还是失败 {#reject-or-fail}

| 决定 | 会发生什么 |
| --- | --- |
| `Decision.reject(code, message)` | 命令被**拒绝**：什么都不写，调用方拿到错误码和消息。用同一个请求 ID 再发一次，仍会被拒绝。 |
| 函数抛出异常 | 命令以 `wow.command.failed`（500）**失败**：什么都不写，重试也同样失败。失败是一个缺陷，绝不是对调用方请求的回答。 |

请求在聚合的当前状态下、或对它的调用方无法满足时拒绝：结账时为空的购物车、要发货却未支付的订单、库存不足、不拥有该购物车的买家。只有程序或它的服务出了问题时，才让函数抛出异常。

只关乎命令本身形状的规则，例如数量不为正、名称为空白、订单没有条目，不是拒绝：它是命令类上的 Jakarta 约束，网关在聚合决定之前以 `wow.command.invalid`（400）拒绝该命令（[编写聚合](./aggregates#check-its-shape)）。于是聚合只为需要它状态的规则声明错误码。

| 规则需要 | 放在哪里 | 调用方得到 |
| --- | --- | --- |
| 只有命令本身 | 命令上的 Jakarta 约束（`@field:Positive`、`@field:NotBlank`、`@field:NotEmpty`、`@field:Valid`） | `wow.command.invalid`（400）及其 `violations` |
| 聚合的状态或调用方 | 带 `@ErrorCode` 的 `Decision.reject` | 该错误码及其状态（默认 422） |

## 声明错误码 {#declare-the-codes}

错误码是一个标注了 `@ErrorCode`、类型为 `String` 的 `const val`，放在嵌套于根类中的一个 `object` 里：

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#rejections -->

```kotlin
/**
 * The codes the aggregate rejects a command with, by their local names: the compiler and the kernel name them
 * `<context>.order.<code>` (decision D259), as its peers read them from the api's generated contract.
 */
object Rejections {
    @ErrorCode(description = "The order's status does not allow the command.", status = 409)
    const val WRONG_STATUS = "wrong-status"

    @ErrorCode(description = "A payment pays the order's total amount.")
    const val WRONG_AMOUNT = "wrong-amount"
}
```

- **名字。** 不带点的错误码是本地名。编译器和内核用上下文和聚合来限定它，所以调用方、回执、HTTP 回答和 Manifest 看到的永远是完整的错误码：`eshop-ordering.order.wrong-status`。带点的错误码按写法就是完整的，用于上下文中各聚合共用的错误码，例如 `eshop-ordering.closed`。
- **状态。** `status` 是用这个错误码拒绝时回答的 HTTP 状态，范围 400 到 599；默认 422。请求与聚合的状态冲突时用 409，请求指向聚合没有的东西时用 404，调用方无权提出这个请求时用 403。
- **说明。** `description` 说明这个错误码的含义。Manifest 会连同错误码一起发布它。
- **规则。** 初始值是一个字符串字面量。错误码不能以 `wow.` 开头，这是框架的前缀；一个模块里每个错误码只声明一次。`Decision.reject` 与 `Rejection.of` 同样拒绝 `wow.` 开头的错误码；若某个模型绕过它们，仍以这样的错误码拒绝命令，命令会以 `wow.command.failed`（500）失败：消息保留，它选的错误码放在扩展 `rejectedCode` 里。

## 用错误码拒绝 {#reject-with-a-code}

命令函数用错误码的本地名和一条给人看的消息返回拒绝：

<!-- sample: example/eshop/inventory/inventory-domain/src/main/kotlin/me/ahoo/wow/example/eshop/inventory/domain/stock/Stock.kt#reserve -->

```kotlin
@OnCommand(produces = [StockReserved::class])
fun onCommand(command: ReserveStock, scope: DecideScope): Decision = when {
    command.orderId in state.reservations -> Decision.NoOp
    command.quantity > state.available -> Decision.reject(
        Rejections.INSUFFICIENT,
        "Only ${state.available} of ${scope.command.ref.id} are available, not ${command.quantity}.",
    )

    else -> Decision.events(StockReserved(command.orderId, command.quantity))
}
```

消息可以写任何对人有帮助的内容：金额、ID、聚合所处的状态。调用方依据错误码做判断，绝不依据消息。

`Decision.reject(code, message, extensions)` 附加结构化数据，客户端读取它们而不必解析消息：一个从名字到 JSON 值的映射，例如阻碍命令的那些东西的 ID。命令的结果把它们放在错误里，HTTP 回答把每一项写成 problem 的一个成员。

## 调用方收到什么 {#what-the-caller-receives}

**在 HTTP 上**，拒绝以错误码的状态回答，响应体是 `application/problem+json`，其 `type` 是 `urn:wow:error:<code>`，`code` 成员是完整的错误码。完整的形状见[线上协议](../reference/protocols)。

**在 Kotlin 中**，命令客户端抛出 `CommandRejectedException`，它的 `code` 是完整的错误码，聚合的拒绝和准入时的拒绝都一样：

<!-- sample: example/eshop/e2e/src/integrationTest/kotlin/me/ahoo/wow/example/eshop/e2e/EshopClientTest.kt#rejected -->

```kotlin
// An order of no items breaks SubmitOrder's constraints: the ordering refuses it at admission, typed.
val rejected = runCatching {
    basket.bean<CommandClient>().of(alice)
        .wait(SubmitOrder(emptyList()), EshopOrderingContract.Order.ref(Caller.DEFAULT_TENANT, "client-alice-2"))
}.exceptionOrNull() as CommandRejectedException
rejected.code.assert().isEqualTo("wow.command.invalid")
rejected.result.error!!.violations.single().path.assert().isEqualTo("items")
```

**对端**用常量而不是字符串来比较错误码。api 模块生成的契约以完整的名字列出其上下文的每个错误码，例如 `EshopOrderingContract.Order.Errors.WRONG_STATUS`，所以依赖 ordering 的 api 的对端，读到的值与 ordering 拒绝时用的值相同。

**发出这条命令的 Saga** 在它的 `@OnRejected` 处理函数中收到拒绝，并决定怎样补偿：见[编写 Saga](./sagas#handle-a-rejection)。

**测试**用 `expectRejected(code)` 期望它，本地名或完整名都可以：见[测试领域代码](./testing#expect)。`aggregateSpec` 运行内核而不经过准入，所以命令的约束在服务器上测试：违反约束的请求回答 400 `wow.command.invalid`，带违反项的路径和约束。

## 框架用来拒绝的错误码 {#codes-the-framework-rejects-with}

有些命令根本到不了聚合的函数：命令格式不对、调用方无权发送、聚合不存在，或者期望版本不匹配。这些都用框架自己的错误码拒绝，它们都以 `wow.` 开头。命令体违反 Bean Validation 约束时，以 `wow.command.invalid`（400）拒绝，problem 会列出每一处违规及其属性路径、约束和消息；应用的类路径上有 Bean Validation 实现时，宿主会检查这些约束。

[错误码](../reference/error-codes)列出了框架的每个错误码及其状态。

---
title: 服务路由
description: 用 @ServiceRoute 以自己的代码提供 HTTP 路由：它的参数、请求体、应答与拒绝，以及 Manifest、OpenAPI 文档和生成的 TypeScript 客户端怎样描述它。
outline: deep
---

# 服务路由

客户端要的大多由命令或查询满足。当一次读取为某个界面而塑形，或一次计算不是任何聚合的命令时，就用服务路由：上下文的一条 HTTP 路由，处理它的是你自己的函数。它和其他路由一样属于服务的契约：Manifest 列出它，边缘层挂载它并接纳它的调用方，OpenAPI 文档描述它，生成的客户端调用它。本页以 eShop 目录的店面为例：它按购物者看到的样子列出一件商品，列出一个类目的商品，并为购物篮的各行报价。

## 声明路由 {#declare-a-route}

路由是一个标注 `@ServiceRoute` 的 `suspend` 成员函数，所在的类由宿主提供实例：

<!-- sample: examples/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/product/Storefront.kt#listing -->

```kotlin
/** The product [id] as the storefront shows it; none (`204 No Content`) while it is not published. */
@ServiceRoute(method = RouteMethod.GET, path = "listings/{id}")
suspend fun listing(call: RouteCall, id: String): ProductListing? {
    val product = call.queries.snapshots<ProductState>().state(id)?.state ?: return null
    return product.takeIf { it.status == ProductStatus.PUBLISHED }?.listing(id)
}
```

- **方法与路径。** `method` 为 `GET`、`POST`、`PUT`、`PATCH` 或 `DELETE`。`path` 位于上下文之下，不以斜杠开头：由小写短横线单词和 `{变量}` 组成。路由挂在 `<METHOD> /<context>/<path>`，这里是 `GET /eshop-catalog/listings/{id}`。
- **名字。** `name` 是路由在 Manifest 中的名字，是它在兼容检查中的身份，也是生成客户端的方法名；留空时取函数名。它是 lowerCamelCase。
- **错误码。** `errors` 列出路由拒绝时用的错误码（[见下文](#refusals-and-error-codes)）。
- **模块。** 函数属于领域模块，由编译器处理：模型处理器把路由连同请求体与应答的 schema 列入上下文的 Manifest，并生成它的绑定。

违反这些规则的路由会被编译器拒绝：函数不是 `suspend`、路径变量没有绑定参数、某个参数什么也没绑定、有两个请求体，或用了路由不能承载的类型。

### 把实例交给宿主 {#give-the-host-its-instance}

生成的绑定在宿主依赖中该类的实例上调用函数：在 Spring Boot 宿主上，就是一个 bean。目录服务在自己的一个配置类中声明了它：

<!-- sample: examples/eshop/catalog/catalog-server/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/server/CatalogRoutes.kt#storefront -->

```kotlin
/** The storefront's service routes: their generated binding calls them on this bean. */
@Bean
fun storefront(): Storefront = Storefront()
```

节点启动时把 Manifest 中的每条路由绑定到它的处理函数；有路由没有处理函数，或有处理函数不对应路由，节点都不会启动。

每个提供该上下文的宿主都需要这个实例，不只是服务自己的服务器：类路径上有该领域模块的第二个应用或测试宿主，缺了它就无法启动，并以指明该类的 `MissingDependencyException` 失败（"No dependency of … Storefront to build the service routes of … Storefront"）。所以模块的第一条路由会改变它的每个宿主，它们要像服务器一样声明这个 bean。不提供该上下文的节点（`wow.contexts.include`、`wow.contexts.exclude`）不构建它的任何路由，也就不需要。

## 参数 {#parameters}

每个参数按种类绑定：

| 参数 | 绑定 |
| --- | --- |
| 以路径变量命名（`{categoryId}`） | 那段路径：`String`、`Int`、`Long`、`Double`、`Boolean` 或枚举；不可为空 |
| 标注 `@RouteQuery` | 同名（或 `@RouteQuery(name)` 所给名字）的查询参数，类型同上：参数可空时可选，否则必填 |
| `RouteCall` | 这次调用：已接纳的调用方、它的租户与作用域、请求 id，以及处理函数以该调用方身份发送的命令和运行的查询 |
| 其余唯一的一个参数，用于 `POST`、`PUT` 或 `PATCH` | 请求体（[见下文](#bodies)） |

店面的类目列表接收一个路径变量、一个可选查询参数和这次调用：

<!-- sample: examples/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/product/Storefront.kt#category -->

```kotlin
/** The published products of [categoryId], cheapest first: [limit] of them, 20 when it is not given. */
@ServiceRoute(
    method = RouteMethod.GET,
    path = "categories/{categoryId}/listings",
    errors = [Refusals.LIMIT_OUT_OF_RANGE],
)
suspend fun categoryListings(call: RouteCall, categoryId: String, @RouteQuery limit: Int?): List<ProductListing> {
    val size = limit ?: DEFAULT_LIMIT
    if (size !in 1..MAX_LIMIT) {
        throw RouteRefusal(Refusals.LIMIT_OUT_OF_RANGE, "A page holds 1 to $MAX_LIMIT products, not $size.")
    }
    val query = listQuery {
        filter {
            ProductStateProperties.STATE__CATEGORY_ID eq categoryId
            ProductStateProperties.STATE__STATUS eq ProductStatus.PUBLISHED
        }
        sort { ProductStateProperties.STATE__PRICE.asc() }
        limit(size)
    }
    return call.queries.snapshots<ProductState>().list(query).map { it.state.listing(it.ref.id) }.toList()
}
```

- 处理函数看不到 HTTP 请求。`call.queries` 以调用方身份读取，所以路由只看得到调用方可见的（[查询快照](./queries)）；`call.commands` 以调用方身份发送（[调用其他服务](./calling-services)）。
- 路径变量或查询参数读不成它的类型，或请求体读不成路由的请求，答以 `400 wow.route.malformed`，其 violation 指明那个参数（请求体为 `body`）。缺少必填查询参数也这样答复。

## 请求体 {#bodies}

`POST`、`PUT` 或 `PATCH` 路由把唯一一个不属于其他种类的参数当作请求体。报价读取购物篮的各行：

<!-- sample: examples/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/product/Storefront.kt#quote -->

```kotlin
/** The price of [request]'s lines, each at its product's price; refused when one is not on the storefront. */
@ServiceRoute(
    method = RouteMethod.POST,
    path = "quotes",
    errors = [Refusals.NOT_ON_SALE, Refusals.QUANTITY_NOT_POSITIVE],
)
suspend fun quote(call: RouteCall, request: QuoteRequest): Quote {
    val products = call.queries.snapshots<ProductState>()
    val lines = request.lines.map { line ->
        if (line.quantity < 1) {
            throw RouteRefusal(
                Refusals.QUANTITY_NOT_POSITIVE,
                "Product [${line.productId}] is quoted ${line.quantity}.",
            )
        }
        val product = products.state(line.productId)?.state?.takeIf { it.status == ProductStatus.PUBLISHED }
            ?: throw RouteRefusal(Refusals.NOT_ON_SALE, "Product [${line.productId}] is not on the storefront.")
        QuotedLine(line.productId, line.quantity, product.price, product.price * line.quantity.toBigDecimal())
    }
    return Quote(lines, lines.fold(BigDecimal.ZERO) { total, line -> total + line.amount })
}
```

请求体和应答一样，是一个自己的类，或这样一个类的 `List`、`Set` 或 `Collection`；映射、标量、裸 `JsonNode` 或流（`Flow`、`Publisher`）会让编译失败。把这些类和命令一起放在 api 模块（`ProductListing`、`QuoteRequest` 和 `Quote` 在 `eshop-catalog-api` 中），Manifest 才带上它们的 schema，同伴服务也才拿得到它们。Jackson 的 `ObjectNode`，即任意形状的 JSON 对象，用于转发一个不归自己的请求体的路由，例如另一个服务的命令；你自己的路由用类，契约才有类型。

## 应答 {#answers}

函数的返回类型就是路由的应答：

| 函数返回 | 路由答复 | Manifest 写明 |
| --- | --- | --- |
| 一个类，或这样一个类的 `List`、`Set`、`Collection` | `200`，值以 JSON 返回 | 它的 `response` schema |
| `Unit` | `204 No Content` | 没有 `response` |
| 可空类型（`ProductListing?`） | `200` 带值；为 `null` 时 `204 No Content` | 它的 `response` schema 与 `responseOptional: true` |

店面的 `listing` 对未发布的商品（草稿或已下架）答 `204`，对调用方看不到的 id 也是如此。路由的客户端把 `204` 读作没有值：生成的 TypeScript 方法解析为 `undefined`（[见下文](#the-generated-typescript-client)）。

## 拒绝与错误码 {#refusals-and-error-codes}

处理函数抛出 `RouteRefusal` 来拒绝一次调用，带上错误码、展示给调用方的消息，以及可选的 `extensions`（problem 会把它们写成自己的成员）。错误码是模块的 `@ErrorCode` 常量，且是上下文的完整错误码：

<!-- sample: examples/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/product/Storefront.kt#refusals -->

```kotlin
/** The codes the storefront refuses a call with: full codes of the context, which its routes list. */
object Refusals {
    @ErrorCode(description = "A page of listings holds 1 to 50 products.", status = 400)
    const val LIMIT_OUT_OF_RANGE = "eshop-catalog.limit-out-of-range"

    @ErrorCode(description = "A quote's product is not published on the storefront.", status = 409)
    const val NOT_ON_SALE = "eshop-catalog.not-on-sale"

    @ErrorCode(description = "A quote's line asks for one or more of its product.", status = 400)
    const val QUANTITY_NOT_POSITIVE = "eshop-catalog.quantity-not-positive"
}
```

- **完整错误码。** 本地名（不含点）会用拒绝它的聚合来限定，而路由不属于任何聚合：路由的错误码写成 `<context>.<code>`，像这里一样声明在聚合之外（[拒绝与错误码](./rejections#declare-the-codes)）。
- **列出它们。** `@ServiceRoute(errors)` 列出路由拒绝时用的每个错误码：Manifest、OpenAPI 文档和客户端从这里读出路由的拒绝。服务合并后的 Manifest 拒绝列出了上下文未声明的错误码。处理函数抛出了路由未列出的错误码，照样答复，节点记录一次日志。
- **答复。** 拒绝答以该错误码的 RFC 9457 problem，状态码取其 `@ErrorCode(status)`；上下文没有为它声明状态码时为 `422`。店面的 `limit=0` 答 `400 eshop-catalog.limit-out-of-range`。
- **转发的答复。** 处理函数放行的 `WowProblemException`，即它调用另一个服务得到的答复，原样带着状态码、错误码与结果传出。

## 谁可以调用 {#who-may-call-it}

边缘层像接纳查询的调用方一样接纳路由的调用方：认证请求，采用它可以在 `Wow-Scope` 中指定的作用域，再询问节点的各个 `ServiceRouteAuthorizer`，第一个拒绝就是答复。一个也没有注册时，每个已认证的调用方都可以调用该上下文的路由。在公共端口上，带 `Wow-Call`（服务的同步调用）的请求被拒绝，答 `403 wow.auth.forbidden`。CoSec 把路由命名为 `<context>:<route>`（[保护部署](../operate/security#where-cosec-decides)）。

## Manifest 怎样描述它 {#what-the-manifest-says}

Manifest 中上下文的 `routes` 列出每条路由（[Manifest 参考](../reference/manifest#serviceroute)）：

| 字段 | 内容 |
| --- | --- |
| `name` | 路由的名字 |
| `route` | 方法与完整路径：`GET /eshop-catalog/listings/{id}` |
| `parameters` | 每个路径变量和查询参数：`name`、读取位置（`in`）、schema，以及是否 `required` |
| `request` | 请求体的 schema；没有请求体时没有 |
| `response` | 应答的 schema；`Unit` 时没有 |
| `responseOptional` | 应答可能缺席（`null` 答 `204`）时为 `true` |
| `errors` | 路由拒绝时用的错误码 |
| `description` | 函数的 KDoc |
| `deprecated` | 函数标注 `@Deprecated` 时为 `true` |

兼容检查按名字把路由与基线比较（[Manifest 与兼容](./manifest)）：

| 变化 | 属于 |
| --- | --- |
| 新增路由、新增可选查询参数、新增或不再列出某个错误码、应答改为必有 | 增量 |
| 删除路由、改变方法或路径、参数改为必填或 schema 改变、新增必填参数 | 破坏性 |
| 新增或删除请求体或应答；请求体接受得更少、应答可能写出更多 | 破坏性 |
| 应答改为可选：期待请求体的读者可能拿到 `204` | 破坏性 |

## OpenAPI 文档怎样描述它 {#what-the-openapi-document-says}

`GET /wow/openapi` 把每条路由描述为其路径与方法上的一个操作（[OpenAPI 与 springdoc](./openapi)）：

- `operationId` 为 `<context>.<name>`（`eshop-catalog.listing`），上下文为它的 tag，名字为 summary，KDoc 为 description。
- 参数是各路径变量和查询参数及其 schema；`requestBody` 为必填，其 schema 是请求体的。
- 应答为带应答 schema 的 `200`；`responseOptional` 的路由在 `200` 旁还有 `204`，没有应答的路由只有 `204`。它列出框架对路由的答复 `400`（`wow.route.malformed`）、`401` 和 `403`，以及 `errors` 中每个错误码的状态码，只带该错误码：类目列表的 `400` 列出 `wow.route.malformed` 与 `eshop-catalog.limit-out-of-range`，报价的 `409` 列出 `eshop-catalog.not-on-sale`，任何路由都不列出其他路由或命令的错误码。`@ErrorCode` 未声明状态码的错误码为 `422`。`x-wow-route.errors` 也是路由自己的错误码。

## 生成的 TypeScript 客户端 {#the-generated-typescript-client}

`wow-generator` 把上下文的路由写进 `routes.ts`：类 `<Context>Routes`，每条路由一个方法，`<Context>Client` 以 `routes` 持有它（[生成客户端](../typescript/generate)）。目录服务的是 `EshopCatalogRoutes`：`listing(id)` 答 `Promise<ProductListing | undefined>`，`categoryListings(categoryId, query?)` 以对象接收查询参数，`quote(body)` 接收请求体。eShop 的 Web 应用这样调用：

<!-- sample: examples/eshop/web/src/shop/storefront.test.ts#listing -->

```ts
const mug = await catalog.routes.listing("mug");
const draft = await catalog.routes.listing("draft");
```

答以 `204` 的 `draft` 为 `undefined`。拒绝以 problem 拒绝 Promise，即带状态码与错误码的 `WowProblemError`：

<!-- sample: examples/eshop/web/src/shop/storefront.test.ts#refusal -->

```ts
const refused = catalog.routes.categoryListings("mugs", { limit: 0 });
await expect(refused).rejects.toMatchObject({
  status: 400,
  code: "eshop-catalog.limit-out-of-range",
});
```

请求体或应答中的 `BigDecimal` 读成文本，与生成客户端的其他地方一样：`ProductListing.price` 是 `string`。

## 测试路由 {#test-a-route}

测试处理函数，就用测试自己的调用去调用它；查询可以是一个真实的查询侧，背后是内存中的快照（`wow-test-query` 的 `memoryQueries`）：

<!-- sample: examples/eshop/catalog/catalog-domain/src/test/kotlin/me/ahoo/wow/example/eshop/catalog/domain/product/StorefrontTest.kt#call -->

```kotlin
/** A shopper's call: its reads run on [queries] as the anonymous caller. */
private class Call(override val queries: Queries) : RouteCall {
    override val caller: Caller = Caller.ANONYMOUS
    override val tenantId: String = caller.tenantId
    override val scope: Scope = caller.scope
    override val requestId: String = "r1"
    override val commands: CommandClient get() = error("The storefront sends no command.")
}
```

`StorefrontTest` 给它 `memoryQueries { snapshot(productModel(), "mug", ProductCreated(...), ProductPublished) }.of(Caller.ANONYMOUS)`，于是每件商品的状态就是它自己的事件构建出的状态，再断言每条路由的应答与拒绝。`CatalogEndToEndTest` 在启动的服务上经 HTTP 调用这些路由：`204`、商品列表项与 `400` problem。

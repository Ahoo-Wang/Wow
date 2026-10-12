# Wow 9.x to V10 Domain Source Migration

Use this reference only when a downstream domain module written against Wow 9.x is rewritten for V10. It maps 9.x constructs to their V10 form; the before/after pairs come from the hand port of the 9.x example (`example-api`, `example-domain`) to the V10 example shop (`shop-api`, `shop-domain`).

Status: first draft. V10 is still moving: verify every annotation, signature, route and configuration key against the pinned V10 target, not against this page. V10 has no release yet; a target pinned to a `v10` commit is a source baseline, not a published artifact, so record the artifact, plugin and repository availability as `MISSING EVIDENCE` until the target tag exists.

## Preconditions and gates

- Upgrade the application to the last 9.x minor first. V10 migrates from that minor only; the migration layer lives through 10.x and leaves in 11.0.
- V10 needs JDK 21, Kotlin with KSP, and Jackson 3 (`tools.jackson`) in domain code that touches JSON.
- Source migration is a semantic rewrite done here; stored history is converted by the data tool `wow-migrate-data`. Never hand-edit stored events to fit the new source.
- Accept the source when the domain compiles under the V10 compiler and its domain tests pass. Accept the data only when every aggregate rebuilt by V10 matches its 9.x snapshot or each difference class is explained and signed off (`references/cutover-evidence.md`).
- Each rewrite that changes an event's or command's name, context, revision or shape becomes an entry of `migration-map.json`, which the data tool checks against the event types in the 9.x store and the V10 Manifest before it imports anything. Its schema is not final; record the 9.x name and revision, the V10 name and revision, and the upgrade function for each entry.
- When a rewrite is uncertain, ask the user, or leave `TODO(wow-migrate)` in code that does not compile. Never guess a semantic rewrite into code that compiles.

## Build and dependencies

| 9.x | V10 |
|---|---|
| `wow-spring-boot-starter` with capability variants (`mongo-support`, `kafka-support`, ...) | `wow-spring-boot` (wiring only) plus one module per technology the host uses: `wow-postgres`, `wow-mongo`, `wow-kafka`, `wow-http`, `wow-cosec` |
| `wow-core`, `wow-spring`, `wow-webflux`, `wow-rest-contract`, `wow-mock`, `wow-metadata`, `wow-schema` | Replaced: `wow-kernel` and `wow-runtime` (core), `wow-http` (routes, problem+json, SSE), `wow-manifest` (contract) |
| `wow-redis`, `wow-bi` | Removed (no Redis backend; ClickHouse sink replaces BI sync) |
| `ksp("me.ahoo.wow:wow-compiler")` by hand | Gradle plugin `id("me.ahoo.wow")`: applies KSP with `wow-compiler` of its own version, excludes the 9.x processors, and adds `generateWowManifestFragment` and `mergeWowManifest` |
| `wow-test` (`AggregateSpec<A, S>`) | The functional `aggregateSpec` DSL; on the pinned target verify which artifact ships it (the shop uses the kernel's test fixtures) |
| Domain handlers returning `Mono`/`Flux` | `suspend`, or keep Reactor through `wow-reactor` (compile error without it) |

Artifact ids keep the `wow-` prefix (`me.ahoo.wow:wow-kernel`); V10 Gradle project names in the Wow repository do not. No module may depend on both a 9.x and a V10 module.

Before, the 9.x example domain:

```kotlin
plugins {
    alias(libs.plugins.ksp)
}
dependencies {
    api(project(":example-api"))
    api(project(":wow-spring"))
    ksp(project(":wow-compiler"))
    testImplementation(project(":wow-test"))
}
```

After, a downstream V10 domain module:

```kotlin
plugins {
    kotlin("jvm")
    id("me.ahoo.wow") version "<pinned V10 version>"
}
dependencies {
    api(project(":shop-api"))
    // The generated models and the binding index return kernel types.
    api("me.ahoo.wow:wow-kernel:<pinned V10 version>")
}
```

The shop itself applies KSP by hand with the plugin's options (`wow.model=generate`, `wow.manifest.module=<project>`), only because the plugin cannot be applied inside the build that produces it. A downstream module uses the plugin. The KSP option `wow.context` is needed only when the module has no single `@BoundedContext`.

## Annotation mapping

V10 annotations keep the package `me.ahoo.wow.api.annotation`, so kept names need no import change, but they lose every 9.x-only attribute. The V10 compiler reports any other annotation of that package as a compile error naming its replacement.

| 9.x | V10 | Rewrite |
|---|---|---|
| `@BoundedContext(name, alias, description, scopes, packageScopes, aggregates)` | `@BoundedContext(name, description)` | The aggregate list comes from `@AggregateRoot`; a fixed tenant moves to host configuration. The context name is part of every command, event and route name, so decide it explicitly (the shop chose the 9.x alias `example` over `example-service`) and record it in the map. |
| `@AggregateRoot(commands = [...])` | `@AggregateRoot(lifecycle = [...])` | Mounted commands have no equivalent. `lifecycle` lists the system commands kept (all five by default). |
| `@OnCommand(returns = [...])` | `@OnCommand(produces = [...])` | Needed only when the function returns `Decision`; a concrete or sealed return type is inferred. |
| `@OnSourcing`, `@CreateAggregate`, `@AllowCreate`, `@AfterCommand(include, exclude)`, `@Blocking`, `@Description`, `@Summary`, `@WowSpi` | Same | Unchanged. |
| `@Name` on a class | Same | Names an aggregate, processor, command or event. |
| `@Name` on a parameter | Same, as a qualifier | The binding index resolves the service by type and qualifier. |
| `@Event(revision = "0.0.1")` | `@Revision(n)` | Integer revision; a migrated 9.x shape is revision 1. |
| `EventUpgrader` (SPI class) | `@Upgrade(event = X::class, from = n)` on a function | See [Events](#events). |
| none | `@Alias("OldName")` | Former names of a command or event, for later renames. |
| none | `@ErrorCode(description, status = 422)` | On a `const val` rejection code. |
| `@StatelessSaga` | `@Saga` | See [Sagas, projections, reactors](#sagas-projections-reactors). |
| `@ProjectionProcessor` | `@Projection` | Same. |
| `@EventProcessor` | `@Reactor` | Same. |
| `@OnEvent(vararg aggregates)`, `@OnStateEvent`, `@OnMessage` | `@OnEvent` (no attributes) | One handler kind, taking a `StateEvent`. |
| `@Retry(...)` | Removed | Retries are subscription configuration. |
| `@OnError` | Removed | Return a rejection; compensate in a `@Saga`. |
| `@PreparableKey` | Removed | `DecideScope.reserve(namespace, key)`. |
| `@AggregateId`, `@TenantId`, `@OwnerId`, `@AggregateVersion` on command fields | Removed (compile error) | See [Command bodies](#command-bodies-carry-no-identity). |
| `@StaticTenantId`, `@StaticAggregateId` | Removed | Fixed tenant is host configuration. |
| `@AggregateName` | `@Name` on the aggregate root | |
| `@AggregateRoute`, `@CommandRoute` (`PathVariable`, `HeaderVariable`) | Removed | Routes come from the Manifest. |
| `@AggregateOwner(OwnerPolicy.*)`, `@Spaced` | Removed | Owner and space come from the principal. |
| `@VoidCommand`, `@Order`, `@EntityObject`, `@ValueObject`, `@Generated` | Removed | No counterpart; drop them. |
| `@QueryReference`, other `api.query.annotation` | Not in V10 yet | Drop and report; the query protocol moves to `wow-query-api`. |
| `@Tag`, `@Schema(name)` (swagger) | Not read | Schemas come from Jackson introspection at build time. |

## Command bodies carry no identity

A command body holds business data only. The aggregate identity is `Command<X>.ref` (`AggregateRef(type, tenantId, id)`), taken from the route or the command message; owner and space are `Command<X>.scope`, taken from the authenticated principal. Delete identity fields from bodies and rewrite every read of them to `command.ref`. A creating command whose id the client chooses uses the route with `{id}`; without it the server generates the id.

Before:

```kotlin
@CommandRoute(action = "package", appendIdPath = CommandRoute.AppendPath.ALWAYS, method = CommandRoute.Method.PATCH)
data class ReceiptOrder(
    @CommandRoute.PathVariable
    @AggregateId
    val id: String,
)

@CommandRoute(action = "package", appendOwnerPath = CommandRoute.AppendPath.NEVER, method = CommandRoute.Method.POST)
data class ShipOrder(
    @CommandRoute.PathVariable
    val id: String
)
```

After:

```kotlin
@Summary("Receive the order")
data object ReceiptOrder

@Summary("Ship the order")
data object ShipOrder
```

Events lose identity fields the same way: the 9.x `OrderCreated.orderId` is now the stream's `ref`, which forces a revision bump and an upgrade function.

## Command functions

| 9.x | V10 |
|---|---|
| `CommandMessage<X>` | `Command<X>` |
| `command.aggregateId.id` | `command.ref.id` |
| `command.header[k]` | `command.headers[k]` |
| `command.ownerId`, `command.spaceId` | `command.scope.ownerId`, `command.scope.spaceId` |
| `ServerCommandExchange<X>` with the body unused | `X` |
| `CommandResultAccessor.setCommandResult(k, v)` | `DecideScope.putResult(k, v)`; returned with this commit only, not persisted, absent from a duplicate's answer |
| `require`/`check`/thrown exception | `Decision.reject(code, message)` with the code declared by `@ErrorCode` |
| `onError(...)` | Delete when it only logs; compensation becomes a `@Saga` |
| `CommandValidator.validate()` on the body | Jakarta constraints at the edge (`wow.command.invalid`, 400); a rule Jakarta cannot express is a rejection in decide |
| Injected service; `@Name("x")` parameter | Parameter of the generated factory, bound once by the host; `@Name("x")` qualifies it |
| `GlobalIdGenerator` inside decide | An id derived from `command.ref` (deciding again after a conflict must produce the same events), or a generator injected as a service; verify the target's `IdGenerator` |
| Return `Any`, event, list, `Mono` | `Decision` (`events`, `NoOp`, `reject`), an event, a list, a sealed type, or a publisher through `wow-reactor` |

9.x framework types in a V10 signature are compile errors, so these rewrites are mandatory. A function's first parameter is the body `X` or `Command<X>`; later parameters are services or a `DecideScope`. Every non-private function is bound; private functions are ignored.

Before, `Order.onCommand(CreateOrder)` in 9.x:

```kotlin
fun onCommand(
    command: CommandMessage<CreateOrder>,
    @Name("createOrderSpec") specification: CreateOrderSpec,
    commandResultAccessor: CommandResultAccessor
): Mono<OrderCreated> {
    val createOrder = command.body
    require(createOrder.items.isNotEmpty()) { "items can not be empty." }
    return Flux.fromIterable(createOrder.items)
        .flatMap(specification::require) // throws InventoryShortageException, PriceInconsistencyException
        .then(
            OrderCreated(
                orderId = command.aggregateId.id,
                items = createOrder.items.map {
                    OrderItem(GlobalIdGenerator.generateAsString(), it.productId, it.price, it.quantity)
                },
                address = createOrder.address,
                fromCart = createOrder.fromCart,
            ).toMono().doOnNext { orderCreated ->
                commandResultAccessor.setCommandResult(OrderState::totalAmount.name, orderCreated.items.sumOf { it.totalPrice })
            }
        )
}
```

After, V10:

```kotlin
@OnCommand(produces = [OrderCreated::class])
suspend fun onCommand(
    command: Command<CreateOrder>,
    inventoryService: InventoryService,
    pricingService: PricingService,
    scope: DecideScope,
): Decision {
    val placing = command.body
    if (placing.items.isEmpty()) {
        return Decision.reject(OrderRejections.NO_ITEMS, "An order needs at least one item.")
    }
    placing.items.forEach { item ->
        val price = pricingService.getProductPrice(item.productId)
        if (price.compareTo(item.price) != 0) {
            return Decision.reject(OrderRejections.PRICE_CHANGED, "The price of ${item.productId} is now $price.")
        }
        val inventory = inventoryService.getInventory(item.productId)
        if (item.quantity > inventory) {
            return Decision.reject(OrderRejections.INSUFFICIENT_INVENTORY, "Only $inventory in stock.")
        }
    }
    val created = OrderCreated(
        items = placing.items.mapIndexed { index, item ->
            OrderItem("${command.ref.id}-${index + 1}", item.productId, item.price, item.quantity)
        },
        address = placing.address,
        fromCart = placing.fromCart,
    )
    scope.putResult("totalAmount", created.totalAmount())
    return Decision.events(created)
}
```

The 9.x `CreateOrderSpec` indirection existed to inject one qualified bean; the port injects the two services directly and turns their `Mono` APIs into `suspend` functions. Keeping the indirection with `@Name("createOrderSpec")` on the parameter is also valid. The host supplies the services by type and qualifier (in the shop, Spring beans).

Rejections are values with stable codes. Declare them beside the aggregate so the compiler lists them in the context's Manifest `errors`; codes must not start with `wow.` and must be unique in the module. The HTTP status defaults to 422.

```kotlin
object OrderRejections {
    @ErrorCode(description = "A product has less stock than the quantity ordered.")
    const val INSUFFICIENT_INVENTORY = "example.order.insufficient-inventory"

    @ErrorCode(description = "The order's status does not allow the command.", status = 409)
    const val WRONG_STATUS = "example.order.wrong-status"
}
```

Before:

```kotlin
fun onCommand(changeAddress: ChangeAddress): AddressChanged {
    check(OrderStatus.CREATED == state.status) {
        "The current order[${state.id}] status[${state.status}] cannot modify the address"
    }
    return AddressChanged(changeAddress.shippingAddress)
}
```

After:

```kotlin
@OnCommand(produces = [AddressChanged::class])
fun onCommand(command: ChangeAddress): Decision =
    if (state.status == OrderStatus.CREATED) {
        Decision.events(AddressChanged(command.shippingAddress))
    } else {
        Decision.reject(OrderRejections.WRONG_STATUS, "The order ${state.id} is ${state.status}.")
    }
```

Keep a domain outcome an event when 9.x recorded it as one: `PayOrder` on a non-payable order still produces `OrderPayDuplicated` because the payment service must refund. Its V10 return type is the sealed `List<OrderPayment>`, so `produces` is inferred.

Ownership checks: `@AggregateOwner(OwnerPolicy.AGGREGATE_ID)` becomes an explicit check on the creating command, after which the kernel's scope guard refuses other owners (`wow.aggregate.forbidden`):

```kotlin
@OnCommand(produces = [CartItemAdded::class, CartQuantityChanged::class])
fun onCommand(command: Command<AddCartItem>): Decision {
    val owner = command.scope.ownerId
    if (owner != null && owner != command.ref.id) {
        return Decision.reject(CartRejections.NOT_OWNER, "The cart ${command.ref.id} is not $owner's.")
    }
    // ...
}
```

## Events

- **Names.** A V10 type is named `context.aggregate.Name`, `Name` being the class's simple name unless `@Name` overrides it. 9.x named types in snake_case from the class (`create_order`, `order_created`) unless `@Name` said otherwise. Do not add `@Name` only to keep the 9.x spelling; map 9.x name to V10 name in `migration-map.json` and leave renames after the migration to `@Alias`.
- **Revisions.** Replace `@Event(revision = "...")` with `@Revision(n)`. When the V10 shape differs from the stored 9.x shape (an identity field removed, a computed property dropped), the V10 class gets a higher revision and an upgrade chain from revision 1. The compiler checks the chain has no gap and no duplicate.
- **Upgrade functions.** `@Upgrade(event, from)` replaces `EventUpgrader`: a top-level function from `ObjectNode` to `ObjectNode` (Jackson 3, `tools.jackson.databind.node`), upgrading revision `from` to `from + 1`.
- **JSON shape.** Computed getters are serialized by Jackson; make them functions (`totalPrice()`) so they leave the event's JSON. `ErrorInfo` on an event becomes plain fields.

Before:

```kotlin
data class OrderCreated(
    val orderId: String,
    val items: List<OrderItem>,
    val address: ShippingAddress,
    val fromCart: Boolean
)

data class OrderItem(/* ... */) : Identifier, CreateOrderItem {
    val totalPrice: BigDecimal
        get() = price.multiply(quantity.toBigDecimal())
}
```

After:

```kotlin
@Revision(2)
@Summary("The order was placed")
data class OrderCreated(
    val items: List<OrderItem>,
    val address: ShippingAddress,
    val fromCart: Boolean,
) {
    fun totalAmount(): BigDecimal = items.fold(BigDecimal.ZERO) { total, item -> total + item.totalPrice() }
}

@Upgrade(event = OrderCreated::class, from = 1)
fun upgradeOrderCreatedFrom1(node: ObjectNode): ObjectNode {
    node.remove("orderId")
    node.get("items")?.forEach { item -> (item as? ObjectNode)?.remove("totalPrice") }
    return node
}
```

Cover each upgrade with a test that decodes a real 9.x payload through the generated model.

## State classes

- `onSourcing` functions must be non-private; V10 ignores private functions, and an event without an evolve function fails when encoded.
- Every event a command can produce needs a sourcing function, even an empty one (`fun onSourcing(event: OrderOverPaid) = Unit`).
- Every property needs a value before creation: the kernel serializes state for snapshots and the test DSL's purity check, and an uninitialized `lateinit` cannot be read. Make it nullable or give it a default.
- Mutable state stays the default style; only annotations and signatures change. Decide must not mutate state outside events; the test DSL fails such code.
- Drop 9.x framework interfaces on state: `StatusCapable`, `StateAggregateTagsExtractor` (tags now change only through the `ApplyTags` lifecycle command), `ReadOnlyStateAggregate`, and swagger `@Schema`.

Before:

```kotlin
class OrderState(val id: String) : StatusCapable<OrderStatus>, StateAggregateTagsExtractor<OrderState> {
    lateinit var items: List<OrderItem>
        private set
    lateinit var address: ShippingAddress
        private set
    // ...
    private fun onSourcing(orderPaid: OrderPaid) { /* ... */ }
}
```

After:

```kotlin
class OrderState(val id: String) {
    var items: List<OrderItem> = emptyList()
        private set
    var address: ShippingAddress? = null
        private set
    // ...
    fun onSourcing(event: OrderPaid) { /* ... */ }

    @Suppress("UnusedParameter")
    fun onSourcing(event: OrderOverPaid) = Unit
}
```

## Sagas, projections, reactors

Target form; check that the pinned target compiler generates subscription bindings before rewriting (the shop's sagas were deferred until it did).

- The class carries exactly one of `@Projection`, `@Reactor`, `@Saga`; its handlers are `@OnEvent` functions or functions named `onEvent`, none private.
- A handler takes `StateEvent<S>` (the commit's events plus the state after it) and optionally its kind's context (`ProjectContext`, `ReactContext`, `SagaContext`), nothing else. Services go to the processor's constructor.
- The source aggregate follows from `S`; select event types inside the handler from `event.events`.
- A projection or reactor returns nothing; a saga returns the commands it derives, a `Command` or a list of them, built with `context.command(ref, body)` so ids and request ids are deterministic. Reactor returns go through `wow-reactor`.
- Each handler is its own subscription named `processor.function`; the processor name is `@Name` or the class name. Derive the subscription names from the 9.x processor names and report them.
- Use `context.executionId` as the idempotency key of external calls; side effects must tolerate at-least-once delivery and cross-aggregate reordering.
- `@Retry` becomes subscription configuration; failures park the aggregate in the failure ledger.

Before:

```kotlin
@StatelessSaga
class CartSaga {
    @Retry(maxRetries = 5, minBackoff = 60, executionTimeout = 10)
    @OnEvent
    fun onOrderCreated(event: DomainEvent<OrderCreated>): CommandBuilder? {
        val orderCreated = event.body
        if (!orderCreated.fromCart) {
            return null
        }
        return RemoveCartItem(productIds = orderCreated.items.map { it.productId }.toSet())
            .commandBuilder()
            .aggregateId(event.ownerId)
    }
}
```

After (target form):

```kotlin
@Saga
class CartSaga {
    @OnEvent
    fun onOrderCreated(event: StateEvent<OrderState>, context: SagaContext): List<Command<*>> {
        val created = event.events.filterIsInstance<OrderCreated>().singleOrNull() ?: return emptyList()
        val customer = event.scope.ownerId
        if (!created.fromCart || customer == null) {
            return emptyList()
        }
        val cart = AggregateType(ExampleContext.NAME, ExampleContext.CART).ref(event.ref.tenantId, customer)
        return listOf(context.command(cart, RemoveCartItem(created.items.map { it.productId }.toSet())))
    }
}
```

A 9.x saga or `onStateEvent` handler that only logs has no V10 counterpart worth keeping; delete it and say so.

## Routing

`@AggregateRoute` and `@CommandRoute` are removed. Every command route is derived from the Manifest:

- command on an existing aggregate: `POST /{context}/{aggregate}/{id}/{command-kebab}`;
- creating command: also `POST /{context}/{aggregate}/{command-kebab}`, the server generating the id;
- state: `GET /{context}/{aggregate}/{id}/state`, with `?version=`;
- lifecycle: `DELETE /{context}/{aggregate}/{id}` and `POST /{context}/{aggregate}/{id}/wow.lifecycle.<kebab>`.

There are no tenant or owner route variants, no `PUT`/`PATCH`, no custom action segments, and no path or header variables bound into bodies. The aggregate segment is the aggregate's name; renaming it with `@Name` renames the aggregate.

Routes of the 9.x example, from its generated OpenAPI, next to the shop's:

| 9.x (example) | V10 (shop) |
|---|---|
| `POST /tenant/{tenantId}/owner/{ownerId}/sales-order` (`CreateOrder`, `action = ""`) | `POST /example/order/create-order`, or `POST /example/order/{id}/create-order` with a client-chosen id |
| `PUT /tenant/{tenantId}/owner/{ownerId}/sales-order/{id}/address` (`ChangeAddress`) | `POST /example/order/{id}/change-address` |
| `POST /tenant/{tenantId}/sales-order/{id}/pay` (`PayOrder`) | `POST /example/order/{id}/pay-order` |
| `POST /tenant/{tenantId}/sales-order/{id}/package` (`ShipOrder`) | `POST /example/order/{id}/ship-order` |
| `PATCH /tenant/{tenantId}/owner/{ownerId}/sales-order/{id}/package` (`ReceiptOrder`) | `POST /example/order/{id}/receipt-order` |
| `DELETE /tenant/{tenantId}/owner/{ownerId}/sales-order/{id}` | `DELETE /example/order/{id}` |
| `PUT /tenant/{tenantId}/owner/{ownerId}/sales-order/{id}/recover` | `POST /example/order/{id}/wow.lifecycle.restore-aggregate` |
| `PUT /tenant/{tenantId}/owner/{ownerId}/sales-order/{id}/tags` | `POST /example/order/{id}/wow.lifecycle.apply-tags` |
| `GET /tenant/{tenantId}/owner/{ownerId}/sales-order/{id}/state/{version}` | `GET /example/order/{id}/state?version={version}` |
| `MockVariableCommand` with path and header variables | No equivalent; dropped |

9.x also served snapshot, event and state-event query, resend, regenerate and compensate routes per aggregate; their V10 counterparts arrive with the query and operations milestones, so check the pinned target and report each one a client uses.

Read the 9.x paths from the application's generated 9.x OpenAPI, not from annotations; every client, gateway rule and CoSec policy that names a 9.x path must change.

## Tenancy and scope

- `@StaticTenantId` and `BoundedContext.Aggregate(tenantId = ...)` become host configuration per aggregate type:

  ```yaml
  wow:
    aggregates:
      "[example.cart]":
        tenant: "(0)"
  ```

- Tenant, owner and space come from the authenticated principal. A principal without a tenant gets `wow.tenant.default` (`(0)`). An administrator acting in another scope sends the `Wow-Scope` header (an RFC 8941 dictionary of `tenant`, `owner`, `space`); a caller without permission gets 403 `wow.auth.forbidden`, never a silent fallback.
- `@AggregateOwner(OwnerPolicy.ALWAYS)` and `@Spaced` disappear: the scope is always recorded from the principal. `OwnerPolicy.AGGREGATE_ID` becomes the creating-command check shown above.
- Ids are unique per tenant only; the data import reports ids that collide across tenants.

## Host configuration

V10 host keys live under `wow.*` but none of the 9.x keys carry over: `wow.service`, `wow.node.*`, `wow.internal.port` (default 9090), `wow.backend` (chosen from the class path), `wow.log.*`, `wow.cluster.*`, `wow.wait.*`, `wow.tenant.default`, `wow.http.*`, `wow.aggregates.*`. Map each 9.x key to a V10 key or record it as removed; never leave a 9.x key in place expecting it to bind.

## REST clients

| 9.x | V10 |
|---|---|
| `Command-Wait-Stage`, `Command-Wait-Context`, `Command-Wait-Processor`, `Command-Wait-Tail-*` | `Wow-Wait`: `hop (">" hop)*`, hops `accepted`, `committed`, `snapshot`, `handled(subscription)`; default `snapshot` |
| `Command-Wait-Timeout` | `Wow-Wait-Timeout` (`500ms`, `10s` or ISO-8601) |
| `Command-Request-Id` | `Wow-Request-Id` |
| `Command-Aggregate-Version` | `If-Match: "<version>"` (412 `wow.aggregate.version-mismatch`); reads answer `ETag` |
| `Command-Tenant-Id`, `Command-Owner-Id`, tenant/owner route prefixes | Principal scope; `Wow-Scope` for cross-scope callers; `Wow-Space` selects among the principal's spaces |
| `Command-Aggregate-Id` | The route's `{id}` |
| `/wow/command/send`, `/wow/command/wait` | Per-command routes above |
| `ErrorInfo` body (`errorCode`, `errorMsg`, `bindingErrors`) | RFC 9457 `application/problem+json`: `type` `urn:wow:error:<code>`, `code`, `violations`, and the full `CommandResult` under `result` once an aggregate ref exists |

Framework codes and statuses: `wow.auth.unauthenticated` 401; `wow.auth.forbidden` and `wow.aggregate.forbidden` 403; `wow.command.malformed`, `wow.command.invalid`, `wow.wait.invalid`, `wow.command.unhandled` 400; `wow.aggregate.not-found` 404; `wow.aggregate.exists`, `wow.command.request-id-reused`, `wow.command.contended` 409; `wow.aggregate.version-mismatch` 409, or 412 with `If-Match`; `wow.aggregate.deleted` 410; `wow.command.busy` 429; `wow.command.unknown`, `wow.node.closing` 503; `wow.subscription.parked` 500; `wow.wait.timeout` 504; domain rejections 422 unless their `@ErrorCode` says otherwise.

Kotlin callers of 9.x `CommandGateway` move to the V10 gateway (`suspend`), or to `ReactiveCommandGateway` from `wow-reactor` to keep Reactor. TypeScript clients are regenerated from the V10 Manifest and rewritten with the `wow-client` skill; do not hand-patch generated clients.

## Tests

Rewrite `AggregateSpec<A, S>({ on { ... } })` to the functional DSL on the generated model: `given` events, `whenCommand` with an optional `scope`, then `expectEvents`, `expectRejected(code)`, `expectState`, `expectScope`. Keep every 9.x scenario; add one for each new rejection code, for the kernel's scope guard where an ownership policy was removed, and for each upgrade function.

```kotlin
class CartSpec {
    private val model = cartModel(ExampleContext.NAME)

    @Test
    fun `a customer should not add to another customer's cart`() = aggregateSpec(model, id = CUSTOMER) {
        whenCommand(AddCartItem("apple"), scope = Scope("mallory")) { expectRejected(CartRejections.NOT_OWNER) }
    }
}
```

## Known gaps

9.x constructs the example port could not carry over. Report each one the application uses, with the decision taken; never emulate one with a compatibility shim.

| 9.x | Status on the V10 target |
|---|---|
| `@AggregateRoute(resourceName)`, custom `@CommandRoute` actions, methods, path and header variables | No equivalent; routes are fixed. Clients and gateway rules change. |
| `@AggregateRoot(commands = [...])`, `MountedCommand`, `@VoidCommand` | No equivalent; dropped. |
| `@AggregateOwner(OwnerPolicy.*)`, `@Spaced` | No per-aggregate policy; scope from the principal plus explicit checks. |
| `CommandValidator.validate()` | No V10 counterpart in the domain API; Jakarta at the edge or a decide rejection. The example's China-only address rule was dropped. |
| `@Order`, swagger `@Tag`/`@Schema` | No equivalent; dropped. |
| `GlobalIdGenerator` inside decide | Deterministic ids from the ref; check the target's `IdGenerator` port before injecting one. |
| `EnumText`, `StatusCapable` (`wow-models`) | No equivalent; plain enum and property. `wow-models` is undecided for V10. |
| `StateAggregateTagsExtractor`, `AbacTags` from state | No equivalent; tags change only through `ApplyTags`. |
| `@EntityObject`, `@ValueObject`, `Identifier` | Removed. |
| `@QueryReference` and other query annotations | No V10 counterpart yet; dropped. |
| `ErrorInfo` on events | Plain data. |
| Private `onSourcing`; events without one; `lateinit` state | Must be rewritten (see [State classes](#state-classes)). |
| `@StatelessSaga` sagas | Wait for subscription bindings in the pinned target; until then mark them `TODO(wow-migrate)` so the build fails. |
| Test DSL artifact | The shop uses the kernel's test fixtures; the separate `wow-test` module is planned. |

Gaps the port reported that later V10 work closed, so do not report them as missing: qualifying an injected service (`@Name` on the parameter), declaring `produces` for functions returning `Decision` (`@OnCommand(produces)`), and declaring rejection codes in the Manifest (`@ErrorCode`). Verify each against the pinned target.

## Completion

Report, besides the general completion items: the pinned 9.x source and V10 target, every changed file, every `TODO(wow-migrate)` and question left for the user, every `migration-map.json` entry, every dropped construct with its replacement or none, every route and header change clients must follow, the compile and domain-test results under the V10 compiler, and the data migration state, which this rewrite alone never proves.

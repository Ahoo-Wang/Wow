---
title: V10 Programming Model
description: Aggregates, commands, events and rejections; sagas, projections and reactors; scheduled commands and other contexts in Wow V10.
outline: deep
---

# V10 Programming Model

A V10 domain module is ordinary Kotlin (or Java) with the annotations of `me.ahoo.wow.api.annotation`. The compiler turns it into kernel models at build time: nothing is scanned or reflected at run time. Every annotation keeps the package of its 9.x namesake, but the set is new and smaller: a 9.x-only annotation on a V10 model is a compile error that points to the migration Skill (`wow-migrate`).

## Five kinds of function

| Function | Shape | May do I/O | When it fails |
| --- | --- | --- | --- |
| Decide | A command function of the aggregate root: the command (or `Command<X>`), then its services and the `DecideScope` | Yes, through its services | The command is rejected or fails; nothing is written |
| Evolve | A sourcing function of the state: one event | No | A model defect: it fails at once |
| React | A `@Reactor` handler: a `StateEvent`, optionally its `ReactContext` | Yes, idempotently | Retried, then the aggregate is parked for the handler |
| Saga | A `@Saga` handler: a `StateEvent`, optionally its `SagaContext`; returns commands | Yes | Retried, then parked |
| Project | A `@Projection` handler: a `StateEvent`, optionally its `ProjectContext` | Yes, guarded by version | Retried, then parked |

A decide, react, saga or project function may be synchronous, `suspend`, or return a Reactor `Mono`, `Flux` or `Publisher`; the compiler picks the call from the signature. A synchronous function is taken for pure computation and runs inline; mark one that blocks (a JDBC write, say) with `@Blocking`, and it runs on the blocking dispatcher, on virtual threads. A function returning a Reactor type needs `me.ahoo.wow:wow-reactor` on the class path, or the module does not compile.

## Aggregates

An aggregate is a root class annotated `@AggregateRoot` and its state. The root is built from the state (`class Order(private val state: OrderState)`), or is its own state.

```kotlin
@AggregateRoot
class Order(private val state: OrderState) {
    @OnCommand(produces = [OrderCreated::class])
    suspend fun onCommand(
        command: Command<CreateOrder>,
        pricingService: PricingService,
        scope: DecideScope,
    ): Decision { /* ... */ }

    fun onCommand(command: PayOrder): List<OrderPayment> { /* ... */ }
}
```

### Command functions

The compiler finds every non-private function annotated `@OnCommand` or named `onCommand`.

- **The first parameter** is the command's body `X`, or the admitted `Command<X>` when the function needs what admission learned: `command.ref` (the aggregate's context, name, tenant and id), `command.scope` (the caller's owner and space), `command.headers` (the request facts the host keeps, `wow.http.request-facts`) and `command.causation`.
- **The parameters after it** are domain services, or the `DecideScope`. A service becomes a parameter of the generated model factory and is bound once, when the host builds the model; the host takes the application's only bean of its type, or its primary one. `@Name("…")` on such a parameter qualifies it: the host takes the bean of that name, so two parameters of one type can take two beans.
- **The return value** is the decision:

| Returns | Means |
| --- | --- |
| `Decision.events(e1, e2, …)` | Those events, appended together as one event stream |
| `Decision.NoOp` | Accepted, changes nothing, nothing is written |
| `Decision.reject(code, message)` | Refused: nothing is written, and the caller gets the code |
| An event, a list or array of events | Those events |
| `Unit`, `null`, an empty list | Nothing changes |

A function that returns `Decision` (or `Any`, or a `Mono<Decision>`) does not tell the compiler which events it produces: list them with `@OnCommand(produces = [...])`, so the Manifest knows. A function whose return type names its events (an event type, a sealed type, a list of them) may not declare them again.

### Creating an aggregate

| Command class | Accepted when |
| --- | --- |
| `@CreateAggregate` | The aggregate does not exist yet; otherwise `wow.aggregate.exists` |
| `@AllowCreate` | Whether or not the aggregate exists |
| Neither | The aggregate exists; otherwise `wow.aggregate.not-found` |

A creating command (`@CreateAggregate` or `@AllowCreate`) gets two routes: one with `{id}`, where the client names the id, and one without, where the server gives it one. Without an id, admission first looks for the aggregate the command's request id already created, and gives the command that id; otherwise the aggregate type's generator draws a new one. A saga's creating command may leave its id to the target the same way, `context.command(type, body)`. A committed id never changes, so an id used as a document number (`SO-20261013-001`, say) stays the one the customer was given. The ids are not gap-free: a strictly consecutive numbering needs an aggregate that hands the numbers out.

A command carries no identity field (no aggregate id, tenant, owner or version in its body): the id comes from the route or from the saga that sends the command, the tenant, owner and space from the caller, and the expected version from `If-Match`.

### What `DecideScope` offers

| Member | Does |
| --- | --- |
| `command` | The command being decided, with its admission facts |
| `putResult(key, value)` | Adds a value to what the caller is answered once the events are appended; not persisted, so a repeated request is not answered it again |
| `reserve(namespace, key)` | Reserves a unique key (a user name, say) for this aggregate; `false` when another aggregate holds it. Released when the events are surely not appended |
| `onAppendFailure { … }` | Undoes an outside act when the events are surely not appended |
| `afterAppend { … }` | Runs once the events are appended; a failure is logged, not raised |
| `schedule(key, at / after, command, target)` | Schedules a command, by default to this aggregate (see [Scheduled commands](#scheduled-commands)) |
| `cancelSchedule(key)` | Cancels the schedule kept under `key` |

### After hooks

A function annotated `@AfterCommand(include = [...], exclude = [...])`, or named `afterCommand`, runs after the command functions it applies to (all of them when `include` is empty). It sees the same state the command function saw and returns events, which follow the command's own in the same stream. It cannot reject; when it fails, the whole command fails and nothing is written.

### Sourcing functions

The state class holds the sourcing functions, annotated `@OnSourcing` or named `onSourcing`, one per event. Mutable state is the default form: the function changes the state in place and returns nothing. A function that returns the state type is the immutable form: it returns the new state.

```kotlin
class CartState(val id: String) {
    var items: List<CartItem> = emptyList()
        private set

    fun onSourcing(event: CartItemAdded) {
        items = items + event.added
    }
}
```

- A model evolves every event its commands produce, so each event needs a sourcing function, even one that changes nothing (`fun onSourcing(event: OrderShipped) = Unit`).
- A sourcing function takes no services and does no I/O.
- Give every property a value before the first event: the kernel serializes the state as it is, for the snapshots and for the test DSL's purity check.
- A decide function or after hook never changes the state. The test DSL compares the state before and after each decision and fails the test when it changed.

### Rejections and error codes

A rejection code is a `const val` of type `String` annotated `@ErrorCode`, in the domain module beside the functions that reject with it. Write its local name, in an `object` nested in the `@AggregateRoot` class (decision D259):

```kotlin
@AggregateRoot
class Order(private val state: OrderState) {
    // … command functions: Decision.reject(Rejections.WRONG_STATUS, "The order is ${state.status}.")

    object Rejections {
        @ErrorCode(description = "The order has no item.")
        const val NO_ITEMS = "no-items"

        @ErrorCode(description = "The order's status does not allow the command.", status = 409)
        const val WRONG_STATUS = "wrong-status"
    }
}
```

The compiler names a local code after its context and aggregate, `example.order.wrong-status`, and the kernel gives a rejection with it that name, so results, receipts, HTTP answers and the Manifest only carry the full code; the test DSL's `expectRejected` takes either. The api module's generated contract holds the full codes for the peers: `ExampleContract.Order.Errors.WRONG_STATUS`. A code with a dot is full, as written: `example.closed` for one the context's aggregates share. The compiler lists the module's codes in the context's `errors` of the Manifest, with their HTTP status (400 to 599, 422 by default) and description. A code may not start with the framework's `wow.`, and a module declares each code once. The kernel's own codes are `wow.command.unhandled`, `wow.aggregate.not-found`, `wow.aggregate.exists`, `wow.aggregate.version-mismatch`, `wow.command.request-id-reused`, `wow.aggregate.deleted` and `wow.aggregate.forbidden` (`KernelRejections`).

### Lifecycle commands

Every aggregate handles five system commands of `me.ahoo.wow.api.lifecycle` unless `@AggregateRoot(lifecycle = [...])` narrows the list (an empty list enables none): `DeleteAggregate`, `RestoreAggregate`, `TransferOwner`, `TransferSpace` and `ApplyTags`. They act on the snapshot's metadata; a deleted aggregate refuses every command but `RestoreAggregate`. Their routes are in the [HTTP API](./http-api.md#lifecycle-routes).

### Owners, spaces and tenants

An aggregate's scope is its owner and its space. The first command's caller gives it; after that, the kernel refuses a command whose caller's owner or space differs from the aggregate's (`wow.aggregate.forbidden`). A part the caller has no value for is not checked: a tenant-level caller, without an owner, reaches every aggregate of its tenant ([Two caller levels](./http-api.md#two-caller-levels)).

`@AggregateRoot(ownership = ...)` says who the aggregate belongs to besides its tenant:

| `Ownership` | The aggregate | Such as |
| --- | --- | --- |
| `OWNER`, the default | Belongs to the owner of the command that creates it, when it has one | An order, a basket |
| `TENANT` | Belongs to its tenant alone: no owner is recorded, and no read or write is restricted by the caller's owner. Its space still applies, and it handles no `TransferOwner` | A product every shopper reads |

```kotlin
@AggregateRoot(ownership = Ownership.TENANT)
class Product(private val state: ProductState) { /* ... */ }
```

The compiler writes the ownership into the Manifest, where query admission reads it. The writes of a `TENANT` aggregate are left to action-level authorization (a `CommandAuthorizer`, CoSec's), which decides who may change a product. A command function that must compare the caller with the aggregate's id (a cart whose id is its customer's user id, say) takes `Command<X>` and reads `command.scope.ownerId`. A fixed tenant for every aggregate of a type is host configuration: `wow.aggregates.[<context>.<aggregate>].tenant`.

### Published streams

The state events of every aggregate are a stream its context publishes for other contexts, as the Manifest's `published` lists it. `@AggregateRoot(published = false)` keeps the aggregate out; `publishState = false` publishes its events without the state after each commit; `topic` names the Kafka topic, `wow.<context>.<aggregate>.state` when empty.

### Event evolution

- `@Revision(n)` gives an event's current revision; an event without it is at revision 1. Adding an optional field with a default is not a new revision.
- `@Upgrade(event = X::class, from = n)` on a top-level function (or a member of an `object`) upgrades the stored JSON of revision `n` to `n + 1`. It takes and returns a Jackson 3 `JsonNode` or `ObjectNode` (`tools.jackson.databind`), is pure and does not suspend. An event at revision `n` needs one upgrade from each of the revisions 1 to `n - 1`; a gap or a duplicate fails the compilation.
- `@Alias("OldName")` keeps a renamed command or event readable: a value without a dot is a former name in the same aggregate, a value with dots a full type name.

```kotlin
@Revision(2)
data class OrderCreated(val items: List<OrderItem>, val address: ShippingAddress, val fromCart: Boolean)

@Upgrade(event = OrderCreated::class, from = 1)
fun upgradeOrderCreatedFrom1(node: ObjectNode): ObjectNode {
    node.remove("orderId")
    return node
}
```

### The Decider DSL

The annotated root is compiled into the kernel's Kotlin DSL, which tests and libraries may also write by hand:

```kotlin
val productModel = aggregate<ProductState>("catalog", "product") {
    initial { ref -> ProductState(ref.id) }
    decide<ChangePrice> { state, command -> Decision.events(PriceChanged(command.price)) }
    evolve<PriceChanged> { event -> price = event.price }
}
```

`decide` takes a `DecideScope` receiver; `decideSuspending`, `after`, `evolveTo` (immutable states), `upgrade`, `alias` and `lifecycle` complete it. A model written this way has no generated binding index or Manifest fragment, so the host does not serve it; services use the annotations.

## Processors: sagas, projections and reactors

A processor is a class annotated with exactly one of `@Saga`, `@Projection` or `@Reactor`. Each of its handlers, annotated `@OnEvent` or named `onEvent`, is a subscription of its own, named `<processor>.<function>`. The processor's dependencies are its constructor's parameters, taken from the application's beans like a command function's services.

A handler takes a `StateEvent<S>`: one committed event stream of an aggregate with its state after the commit. A handler that cares about one kind of event takes a `StateTypedEvent<S, E>` instead, or the event `E` itself, and is called once for each `E` of the stream, in order (decision D264): `event` is that event, `eventIndex` its place in `events`, and `state` is still the state after the whole commit.

| Property | Holds |
| --- | --- |
| `ref`, `version` | The aggregate and its version after the commit; `id` is `ref@version` |
| `events` | The stream's events, decoded and upgraded to their current revision |
| `state` | A copy of the state after the commit, never the live instance |
| `commandId`, `requestId`, `commandType`, `commitTime` | The command that produced it, and when (epoch milliseconds) |
| `scope` | The aggregate's owner and space after the commit, not the caller's |
| `causation`, `headers` | The chain the command belongs to, and the request facts it carried; `correlationId` is the root command's id |

`S` selects the aggregates: a handler reads the aggregates of the module's context whose state type is `S`, unless `@OnEvent(aggregates = ["<context>.<aggregate>"])` names them. A handler of each `E` needs neither: `E` names the aggregate, and the compiler writes its events into the subscription's source, so the node passes over a stream without one, its state not even decoded. Several `E` of one stream share its checkpoint: a failure retries the stream, calling the handler for each again. For another context's events, `E` is that context's api class, and `@OnEvent(aggregates)` names its aggregate (see [Reading another context's stream](#reading-another-context-s-stream)).

The handler's context gives it an `executionId`, `subscription:ref@version`, the same every time the same commit is handled by the same subscription, and an `invocationKey`, the idempotency key for an outside call: the `executionId` for a handler of the stream, `executionId:eventIndex` for a handler of each `E`.

### Projections

A projection writes a read model from committed events. It returns nothing; its writes are guarded by version, since a commit may be handled more than once.

```kotlin
@Projection
class ProductSummaryProjection(private val summaries: ProductSummaries) {
    @Blocking
    @OnEvent
    fun onEvent(event: StateEvent<ProductState>) {
        summaries.upsert(event.ref.id, event.state.name, event.state.price, ifNewer = event.version)
    }
}
```

The snapshot is already a read model: every aggregate keeps one, queried at `/{context}/{aggregate}/snapshot/query`. Write a projection when the read model has another shape or lives in another store.

### Reactors

A reactor acts on committed events (it sends a notification, calls another system) and returns nothing. Use `context.invocationKey` as the idempotency key of the outside call:

```kotlin
@Reactor
class OrderNotifier(private val mail: Mail) {
    @OnEvent
    suspend fun onShipped(event: StateTypedEvent<OrderState, OrderShipped>, context: ReactContext) {
        mail.send(event.state.customer, "Order shipped", key = context.invocationKey)
    }
}
```

A reactor cannot send a command: a processor that sends commands is a saga.

### Sagas

A saga derives commands from committed events. Its handler returns one `Command`, a list of them, or `null`, an empty list or nothing (`Unit`) for none; `SagaContext.command(ref, body)` builds each one:

```kotlin
@Saga
class CartSaga {
    @OnEvent
    fun onOrderCreated(event: StateTypedEvent<OrderState, OrderCreated>, context: SagaContext): Command<RemoveCartItem>? {
        val created = event.event
        val owner = event.scope.ownerId ?: return null
        return context.command(
            AggregateType("example", "cart").ref(event.ref.tenantId, owner),
            RemoveCartItem(created.items.mapTo(linkedSetOf()) { it.productId }),
        )
    }
}
```

- A derived command's id and request id are fixed by the execution and the order of the calls, so handling the commit again derives the same requests, and they are decided once.
- The saga's hop is done once its commands are committed in its own context, or, across contexts, once the broker has them.
- A derived command carries the scope (owner and space) and the operator of the commit it is derived from, and the tenant of the `ref` the saga gives it. An aggregate it creates belongs to the same owner: a basket's checkout creates an order that belongs to the basket's buyer. The target's scope guard checks the scope, so a command to an aggregate of another owner is refused, unless the target belongs to its tenant (`Ownership.TENANT`, a stock, say). A saga may narrow the scope of a command it returns: the shop's `CartSaga` returns `command.copy(scope = command.scope.copy(spaceId = null))`, since a cart has no space while an order may. A compensation (`@OnRejected`) and a scheduled command carry the scope of the command they follow the same way.
- `context.command(type, body)`, with an `AggregateType` instead of a ref, derives a creating command that leaves the aggregate's id to the target's admission: the target gives it the id of the aggregate its request already created, else a new one, so handling the commit again keeps the first id. `context.ids` draws an id in the saga instead; handling the commit again draws a new one, so use it only for the creating command it names, whose request id keeps the target from creating the aggregate twice.
- When the handler's return type does not name the commands (a `List<Command<*>>`, say), declare their classes with `@OnEvent(derives = [ConfirmPayment::class, FailPayment::class])`, so a wait can follow the saga's hop into their contexts. A sealed type stands for its subclasses, both in `derives` and in a return type `Command<S>`, which then needs no `derives`. A class the service's build finds no context deciding, in the service or in a peer api on its class path, fails the build.

### Rejections a saga expects: `@OnRejected`

A derived command may be rejected: the cart was deleted, the stock is short. A rejection is a business outcome, so the saga handles it in an `@OnRejected` handler, which takes a `CommandRejected<C>` of the command's type (or `CommandRejected<*>` for every command the saga derives) and, optionally, the `SagaContext`, and returns the commands that compensate:

```kotlin
@OnRejected
fun onReserveRejected(rejected: CommandRejected<ReserveStock>, context: SagaContext): Command<CancelOrder> =
    context.command(rejected.executionId.ref, CancelOrder("${rejected.ref.id} cannot be reserved: ${rejected.error.message}"))
```

`CommandRejected` names the command (`commandId`, `commandType`, `ref`), the `error` (code, message, violations), the `causation` and the `executionId` of the saga's execution that derived it; it does not carry the command's body. The runtime hands each rejection over once. A rejection the saga has no handler for parks the aggregate for the saga's subscription, as a failure would. A handler that returns nothing (`Unit`, or `null`) accepts the rejection without compensating, and derives no command; an `@OnCommitted` handler may return `Unit` too.

### Where a subscription starts

A subscription with no checkpoint in a partition starts by its kind: a projection from the beginning of the log (it fills in the history), a reactor or a saga from the end (going live does not resend commands for old events). Override it per subscription, in the host:

```yaml
wow:
  subscriptions:
    "[OrderStockSaga.onOrderShipped]":
      start: beginning # or end, or an ISO-8601 instant
```

The setting applies only while the subscription has no checkpoint; after that it goes on from its checkpoint.

### Failures

A handler that throws is retried (`wow.subscription.retry`: 5 attempts, from 100 ms up to 5 s by default). After the last attempt the aggregate is parked for that subscription in the failure ledger: the subscription goes on with other aggregates, and the parked one waits until it is resumed or skipped. The runtime offers both operations; no route or console exposes them yet.

A subscription can have a budget of its own, for a handler that calls a slow external system. `timeout` bounds one attempt: an attempt that runs longer is cancelled and counts as a failed attempt. `@Retry` declares it on a processor class, for all its handlers, or on an `@OnEvent` handler; a handler's settings override its processor's one at a time, and a setting left out is not given. Durations are Kotlin's (`500ms`, `2s`) or ISO-8601 (`PT2S`):

```kotlin
@Reactor
@Retry(maxAttempts = 3, timeout = "2s")
class ShippingNotifier(private val carrier: CarrierClient) {
    @OnEvent
    @Retry(initialBackoff = "1s")
    suspend fun onShipped(event: StateTypedEvent<OrderState, OrderShipped>) = carrier.notify(event.state.id)
}
```

The compiler checks the settings, writes the budget into the generated binding and into the subscription's entry of the Manifest (`retry`). The host's configuration overrides it per subscription; each setting given replaces the annotation's, and the ones neither gives stay the global ones:

```yaml
wow:
  subscription:
    retry:
      max-attempts: 5 # the global budget
  subscriptions:
    "[ShippingNotifier.onShipped]":
      retry:
        max-attempts: 3
        initial-backoff: 1s
        timeout: 2s
```

A `@Blocking` handler runs on a virtual thread that a timeout cannot stop: its attempt counts as failed when the call returns. The budget only decides when the aggregate is parked; how parked entries are retried afterwards (`wow.subscription.parked.*`) does not change.

## Scheduled commands

A decide function or a saga can schedule a command for later: an order that cancels itself when unpaid, a reminder. The schedule is part of the commit: it is written with the events, or not at all.

```kotlin
@OnCommand(schedules = ["shop.order.CancelOrder"])
fun onCommand(command: PlaceOrder, scope: DecideScope): OrderPlaced {
    scope.schedule("pay-timeout", 30.minutes, CancelOrder("unpaid"))
    return OrderPlaced(command.items)
}

@OnCommand(produces = [OrderPaid::class])
fun onCommand(command: PayOrder, scope: DecideScope): Decision {
    scope.cancelSchedule("pay-timeout")
    return Decision.events(OrderPaid(command.amount))
}
```

- `schedule(key, at, command, target)` or `schedule(key, after, command, target)`: `key` names the schedule within the aggregate; scheduling a key again reschedules it; `target` is this aggregate by default.
- In a saga, `SagaContext.schedule(key, at / after, target, command)` and `cancel(key, target)`.
- Declare the scheduled commands' full names in `@OnCommand(schedules = [...])` or `@OnEvent(schedules = [...])`: the compiler cannot read the calls in a function's body, and the Manifest lists them.
- The command is sent at least once, never before it is due, possibly a little later (after a handover or a backlog), with the precision of `wow.timers.poll-interval`. Its request id is the timer's id, so it is decided once.
- When it is due, the target decides it on its state then. A rejection is a normal guard (the order was paid meanwhile): the timer is done, and the rejection does not go to `@OnRejected`. A technical failure is retried (`wow.timers.max-attempts`), then parked under `wow.timers`.
- Deleting an aggregate cancels its pending schedules. A scheduled command is not part of the wait of the command that scheduled it.

## Other contexts

Each context is served by its own service; services talk over Kafka, never over each other's databases or cluster channels.

### Reading another context's stream

A handler names the other context's aggregate in `@OnEvent(aggregates = [...])` and declares its own view of the state:

```kotlin
@Saga
class OrderStockSaga {
    @OnEvent(aggregates = ["example.order"])
    fun onOrderShipped(
        event: StateTypedEvent<OrderView, OrderShipped>, // OrderShipped: the order context's api class
        context: SagaContext,
    ): List<Command<CommitReservation>> =
        event.state.items.map { context.command(STOCK.ref(event.ref.tenantId, it.productId), CommitReservation(event.ref.id)) }
}

data class OrderView(val items: List<Item> = emptyList()) {
    data class Item(val productId: String, val quantity: Int)
}
```

- The handler reads the topic the upstream publishes, `wow.<context>.<aggregate>.state`, with its offsets in this service's checkpoints. The node needs `wow-kafka` and `wow.kafka.bootstrap-servers`.
- Its `events` are the classes of the upstream's api module (DX-1c), decoded when the event's revision is the one the api's contract declares; any other event is a `PublishedEvent` (`me.ahoo.wow.api.subscribe`): its `name` (`example.order.OrderShipped`), `revision` and JSON `payload`. A handler of each `E` (above) takes the api's class, names the aggregate in `@OnEvent(aggregates)`, and is not called for a `PublishedEvent`; the node does not start when the contract on its class path declares no such event.
- Its `state` is decoded from the published state into the handler's own type. When the upstream publishes its events without the state (`publishState = false`), declare it nullable (`StateEvent<OrderView?>`): a non-nullable view of a stream without state fails at startup.
- A saga or reactor on another context's stream starts at the end by default. A service that must not miss what was published before it first started sets `start: beginning` for the handler (see [Where a subscription starts](#where-a-subscription-starts)).
- The service takes the upstream's Manifest as a peer (below).

### Sending a command to another context

A saga sends another context's command like any other, with that context's API module on its class path:

```kotlin
context.command(AggregateType("inventory", "stock").ref(event.ref.tenantId, productId), ReserveStock(event.ref.id, quantity))
```

- The command goes to the target context's command topic, `wow.<target>.commands`. The saga's hop is done once the broker has it.
- The target's service executes its command topic when it sets `wow.kafka.command-intake: true`. The topic must exist with exactly as many partitions as the target's log (`wow.log.partitions`, 64 by default), or that node does not start.
- A rejection comes back as a receipt on the sender's `wow.<sender>.receipts` and goes to the saga's `@OnRejected` handler.
- The sender finds the command's type name in the target's Manifest, a peer of the sending service.
- The tenant of the `ref` crosses as it is: every context of a system shares one tenant space (see [Deployment](../../operate/security.md#tenants)).

A node without a command transport does not start when one of its sagas derives, schedules or handles the rejections of another context's commands. The message names each saga and context:

```text
Node <node> has no command transport (with Spring Boot, set wow.kafka.bootstrap-servers), but example/OrderSaga.onOrderCreated sends commands to inventory: a command across contexts goes through its context's command topic (decision D33).
```

In production the transport is Kafka (`wow.kafka.bootstrap-servers`). To try a service out on one machine without Kafka, set `wow.topics.in-memory: true`: the node keeps its command topics and published streams in its JVM, executes its own command topic, and warns at startup; nothing reaches another JVM or survives a restart (DX gap G27). A test without Kafka can give the node in-memory topics itself: an `InMemoryTopics` bean (`me.ahoo.wow.kernel.memory`) and a `SourceTopics` bean over it (`me.ahoo.wow.runtime.node`), as the shop's tests do:

```kotlin
class InMemoryShopTopics {
    @Bean
    fun inMemoryTopics(): InMemoryTopics = InMemoryTopics(1)

    @Bean
    fun sourceTopics(topics: InMemoryTopics): SourceTopics = SourceTopics(topics, producer = topics)
}
```

When the saga needs the answer now, because the target being online is a precondition, it calls the target's gateway instead (`SagaContext.call`, decision D193) and declares the command classes it calls, so the Manifest lists them, a wait can follow the call, and the call is refused for any other class (decision D259):

```kotlin
@OnEvent(calls = [SubmitOrder::class])
suspend fun onBasketCheckedOut(event: StateTypedEvent<BasketState, BasketCheckedOut>, context: SagaContext) {
    val checkedOut = event.event
    context.call(context.command(EshopOrderingContract.Order.ref(event.ref.tenantId, checkedOut.orderId), SubmitOrder(items)))
}
```

A call needs no command topic: the node reaches the target at `wow.calls.contexts.<context>.url` (or `wow.clients.contexts.<context>.internal-url`), and does not start when a saga calls a context without that URL, or a command no Manifest it knows decides. A command of the saga's own context is derived, never called.

### Calling from Java

The command and query clients (`CommandClient`, `QueryApi`) are Kotlin, with `suspend` functions and flows. Java code calls them through the facade of `wow-client`: each call is blocking, or `…Async` with a `CompletableFuture`; lists come back as `List`, streams as `java.util.stream.Stream`, and timeouts are `java.time.Duration`:

```java
JavaCommandClient commands = JavaCommandClient.of(commandClient).of(caller);
CommandResult submitted = commands.sendAndWait(new SubmitOrder(items), EshopOrderingContract.Order.ref(tenant, id),
    JavaCommandClient.options().timeout(Duration.ofSeconds(5)).build());

JavaSnapshotQueries<OrderState> orders = JavaQueryApi.of(queryApi).of(caller).snapshots(OrderState.class);
CompletableFuture<Integer> open = orders.listAsync(new ListQuery(MatchAllFilter.INSTANCE)).thenApply(List::size);
```

A rejection is a `CommandRejectedException` (with its `code`), a technical failure a `WowProblemException`, both unchecked; an asynchronous call's future completes with them. Call the blocking forms from your own threads, never from a Netty or WebFlux event loop.

### Peers

A service that sends another context's commands, or reads its stream, takes that service's merged Manifest as a peer. With the plugin, in the server module:

```kotlin
dependencies {
    wowPeers(project(":inventory-server"))            // a project of the same build
    wowPeers("com.example:inventory-server:1.0.0")    // or a published service
}
```

`collectWowPeers` copies each peer's Manifest to `META-INF/wow/peers/<service>.json` in the module's resources, with an index beside them. Two services may be each other's peers.

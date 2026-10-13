---
title: What the V10 Compiler Generates
description: The model factories, processor bindings, the ModelProvider binding index, the Manifest fragments and the routes the Wow V10 compiler generates.
outline: deep
---

# What the V10 Compiler Generates

`wow-compiler` is a KSP processor. In `generate` mode (the Gradle plugin sets it) it reads a domain module's annotated classes and writes Kotlin sources and resources into `build/generated/ksp/main/`. Nothing is found by scanning or reflection at run time: the host loads what the compiler wrote. A model the compiler cannot bind fails the compilation with a message naming the function and the rule.

For the shop's `shop-domain` module, the compiler writes:

```text
build/generated/ksp/main/
├── kotlin/me/ahoo/wow/example/shop/domain/
│   ├── ShopDomainModelProvider.kt          the binding index
│   ├── cart/CartModel.kt                    fun cartModel(context, configure)
│   ├── cart/CartSagaSubscriptions.kt        fun cartSagaHandlers(dependencies)
│   ├── order/OrderModel.kt                  fun orderModel(context, pricingService, configure)
│   └── order/OrderSagaSubscriptions.kt      fun orderSagaHandlers(dependencies)
└── resources/META-INF/
    ├── services/me.ahoo.wow.kernel.ModelProvider
    └── wow/manifest/shop-domain.json        the Manifest fragment
```

## Model factories

Each aggregate root gets a top-level factory function in the root's package, named after the root: `<root>Model`, its first letter in lower case (`Order` gives `orderModel`).

```kotlin
public fun orderModel(
    context: String,
    pricingService: PricingService,
    configure: AggregateModelBuilder<OrderState>.() -> Unit = {},
): AggregateModel<OrderState>
```

- **`context`** comes first, even when the module declares one `@BoundedContext`: the binding index passes the context it was compiled for. A test passes it itself: `orderModel("example", pricing)`.
- **The services** follow, one parameter per service a command function or after hook takes. The host binds them once, when it builds the model.
- **`configure`** runs on the model builder after the generated bindings: a general extension point, rarely needed.

The factory builds the model through the kernel's DSL (`aggregate<S>(context, name) { … }`): one `decide` (or `decideSuspending`) per command with its creation rule, one `evolve` (or `evolveTo`) per event with its revision, one `upgrade` per `@Upgrade` function, the aliases and the lifecycle commands.

## Processor bindings

Each `@Saga`, `@Projection` or `@Reactor` class gets `<processor>Handlers(dependencies)` in its package (`OrderSaga` gives `orderSagaHandlers`), in a file named `<Processor>Subscriptions.kt`. It builds the processor once, its constructor's parameters taken from `dependencies` by type (and qualifier), and returns one kernel `ProcessorBinding` per handler, named `<processor>.<function>`, with the saga's `@OnRejected` handlers bound to it. The generated code builds on `wow-kernel` only: the runtime turns the bindings into subscriptions.

## The binding index: `ModelProvider`

Each module gets one class implementing `me.ahoo.wow.kernel.ModelProvider`, registered in `META-INF/services/me.ahoo.wow.kernel.ModelProvider` for `ServiceLoader`:

```kotlin
public class ShopDomainModelProvider : me.ahoo.wow.kernel.ModelProvider {
    override val context: String = "example"
    override val fragment: String = "META-INF/wow/manifest/shop-domain.json"

    override fun models(dependencies: Dependencies): List<AggregateModel<*>> = listOf(
        cartModel(context),
        orderModel(context, dependencies.get(PricingService::class.javaObjectType)),
    )

    override fun processors(dependencies: Dependencies): List<ProcessorBinding<*>> =
        listOf(cartSagaHandlers(dependencies), orderSagaHandlers(dependencies)).flatten()
}
```

- **Its name** comes from the module (the KSP option `wow.manifest.module`, the Gradle project's name under the plugin): `shop-domain` gives `ShopDomainModelProvider`, `eshop-catalog-domain` gives `EshopCatalogDomainModelProvider`. A name that does not start with a letter gets the prefix `Module`.
- **Its package** is the deepest package all of the module's models and processors share.
- **Its dependencies** are looked up by type, and by qualifier for a parameter with `@Name`. On the host, `Dependencies` is the application's bean factory: a qualified dependency is the bean of that name; an unqualified one is the only bean of its type, or the primary one among several. A missing bean fails the start, naming the type (`MissingDependencyException`).

The host loads every `ModelProvider` on its class path (`ModelProvider.load()`) and serves every model they build. A service's class path therefore decides the contexts it serves.

## The Manifest fragment

The compiler writes the module's part of the service's contract to `META-INF/wow/manifest/<module>.json`. It names the context and, for each aggregate:

| Field | Holds |
| --- | --- |
| `name`, `description` | The aggregate's name and its `@Description` |
| `state` | A reference to the state's schema, and once completed its fingerprint |
| `snapshot` | Whether the aggregate keeps snapshots |
| `commands` | Each command's `name`, `aliases`, `schema`, `creation` (`REQUIRED`, `ALLOWED`, `NONE`), `produces` and `routes` |
| `events` | Each event's `name`, `aliases`, `revision` and `schema` |
| `lifecycle` | The lifecycle commands it handles (`wow.lifecycle.DeleteAggregate`, …) |
| `queries` | Its query models (`snapshot`, `events`) and their routes |
| `schedules` | The commands its decide functions may schedule |

Beside the aggregates, the context lists its `subscriptions` (each processor handler with its `kind`, `source`, `derives`, `rejected`, `schedules` and `start`), its `published` streams (`aggregate`, `topic`, `includeState`) and its `errors` (each `@ErrorCode` with its `status` and `description`).

The compiler writes the schemas as references (`#/$defs/<JVM class name>`). The plugin's `generateWowManifestFragment` completes them: it introspects each type with Jackson's rules and writes the JSON Schemas (2020-12) into `$defs`, with the query keywords (`x-wow-*`) and the state's fingerprint. The completed fragment replaces the compiler's in the module's resources. The snapshot queries read the state schema from it, so a state field can be filtered and sorted only once the fragment is completed.

## The service's Manifest

The host merges the fragments on its class path into the service's Manifest when it starts, served at `GET /wow/manifest`, with the framework's error codes at the top. The plugin's `mergeWowManifest` merges the same fragments at build time into `build/wow/manifest.json`, named after `wow.serviceName`, which other services take as a peer (`wowPeers`) and which generated clients read.

## Routes

The host builds its routes from the Manifest alone; the compiler decides them.

| Route | From |
| --- | --- |
| `POST /<context>/<aggregate>/{id}/<command>` | Every command; `<command>` is its name in kebab case (`ChangePrice` is `change-price`) |
| `POST /<context>/<aggregate>/<command>` | A creating command (`@CreateAggregate`, `@AllowCreate`) also; the server generates the id |
| `DELETE /<context>/<aggregate>/{id}` | `DeleteAggregate` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.<command>` | The other lifecycle commands (`wow.lifecycle.restore-aggregate`, `transfer-owner`, `transfer-space`, `apply-tags`) |
| `GET /<context>/<aggregate>/{id}/state` | The aggregate's state |
| `POST`, `GET /<context>/<aggregate>/snapshot/query` | The snapshot query model and its descriptor |
| `POST`, `GET /<context>/<aggregate>/events/query` | The event query model and its descriptor |

[HTTP API](./http-api.md) describes what each route takes and answers.

---
title: What the Compiler Generates
description: The model factories, processor handlers, query field constants, binding index and Manifest fragment the compiler writes for a domain module, and the contract code it writes for its api module.
outline: deep
---

# What the Compiler Generates

The Wow compiler is a KSP processor. It reads a domain module's annotated classes at build time and writes Kotlin sources and resources beside them, in `build/generated/ksp/main/`. Nothing is found by scanning or reflection when the service runs: the host loads what the compiler wrote. A model the compiler cannot bind fails the build with a message that names the function and the rule. This page lists what it writes and where each part is used.

For the eShop's ordering domain module, the compiler writes, among others:

```text
build/generated/ksp/main/
├── kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/
│   ├── EshopOrderingDomainModelProvider.kt    the binding index
│   ├── OrderModel.kt                          fun orderModel(context, paymentPolicy, configure)
│   ├── OrderProcessSagaSubscriptions.kt       fun orderProcessSagaHandlers(services… | dependencies)
│   └── OrderStateProperties.kt                the state's query fields
└── resources/META-INF/
    ├── services/me.ahoo.wow.kernel.ModelProvider
    └── wow/manifest/eshop-ordering-domain.json    the Manifest fragment
```

## Model factories

Each aggregate root gets a function named after it, `<root>Model` with a lower-case first letter: `Order` gives `orderModel`. Its parameters are the context, which defaults to the module's, then one per domain service the command functions take, then an optional `configure` block on the model builder. A test builds the model with it directly: `orderModel(paymentPolicy = PaymentPolicy(30.minutes))`. The host passes the services from the application's beans. Beside it, `<Root>Model` (`OrderModel`) holds the aggregate's `CONTEXT`, `NAME` and `TYPE`, for the domain module, which does not see its own contract.

## Processor handlers

Each `@Saga`, `@Projection` or `@Reactor` class gets `<processor>Handlers(services…)`, which takes its constructor's services as named parameters, and `<processor>Handlers(dependencies)`, which finds them in the host's `Dependencies`: `OrderProcessSaga` gives `orderProcessSagaHandlers`. Each builds the processor once and returns one binding per handler, named `<processor>.<function>`, with the saga's `@OnRejected` and `@OnCommitted` handlers attached. A processor test passes its fakes as the services (`orderSummaryProjectionHandlers(summaries = …)`, [Test Domain Code](./testing)); a missing dependency names the processor or model it was building.

## Query fields

With `wow-query-api` on the module's class path, each state gets `<State>Properties`: one `QueryField` constant per path a snapshot query can name, such as `ProductStateProperties.STATE__PRICE` for `state.price`, beside the system fields of `SnapshotProperties` ([Query Snapshots](./queries#write-a-query-in-kotlin)).

## The binding index

Each module gets one class implementing `ModelProvider`, registered for `ServiceLoader`, named after the module: `eshop-ordering-domain` gives `EshopOrderingDomainModelProvider`. It names the module's context and fragment, builds its models and its processors from `Dependencies`, and the host loads every one on its class path. It also records the binding it was generated for: a module built by a compiler of another Wow version is refused at startup, with a message that says to rebuild it, instead of failing on its first command. A test can load it to check what the module builds ([Test Domain Code](./testing#what-the-compiler-generated)).

## The Manifest fragment

The compiler writes the module's part of the service's contract to `META-INF/wow/manifest/<module>.json`, named by the module's project path with its segments as directories (`:eshop:ordering-domain` writes `eshop/ordering-domain.json`, `:eshop-ordering-domain` `eshop-ordering-domain.json`, the root project `.root.json`), so two projects never share one: the context, its aggregates with their commands, events, lifecycle commands, query models and schedules, its subscriptions with what each derives, schedules and calls, its published streams and its error codes. Its type schemas are references until the Gradle plugin completes them with the JSON Schemas Jackson's rules give, the query keywords included. [The Manifest and Compatibility](./manifest) covers what happens to it next.

## The contract code of the api module

The api module declares its domain module, `wowContract(project(":ordering-domain"))` under the plugin `me.ahoo.wow.api`, and the plugins select from the fragment what a peer needs, the commands, events, state schemas and errors of each context, as `META-INF/wow/contract.json`, the api module's contract. The contract also names the service that hosts the contexts, as the domain module's build declares it:

<!-- sample: fragment -->

```kotlin
wow {
    service = "eshop-ordering-service"
}
```

A peer that depends on the api module finds the service by that name, and the service refuses to start under any other `spring.application.name`. The service's own Manifest takes its name from the contracts of the contexts it hosts, so the server module that hosts them does not repeat it; contracts that name different services fail its build, and with none it is named after the server's project path. The eShop's domain modules declare it the same way, as the ordering's `wow { service = "eshop-ordering-service" }` does.

An api module also compiles `<Context>Contract` from it, such as `EshopOrderingContract`:

| Member | Holds |
| --- | --- |
| `NAME` | The context's name |
| `<Aggregate>.TYPE`, `<Aggregate>.ref(tenant, id)` | The aggregate's type, and a ref to one of its aggregates |
| `<Aggregate>.Errors.*` | Its error codes, by their full names |
| `<Aggregate>.STATE__*` | The query fields of its state, with `wow-query-api` |

A peer writes no path, name or code by hand: `EshopOrderingContract.Order.ref(tenant, id)` addresses an order, and `EshopOrderingContract.Order.Errors.WRONG_STATUS` compares a rejection ([Call Other Services](./calling-services), [Rejections and Error Codes](./rejections#what-the-caller-receives)).

The contract and this code are the api module's feature `contract`, published within the module as the jar of classifier `contract`; the module's own jar holds its types alone, so every module compiles against the types without waiting for any domain, the api's own domain module among them. Who sees the contract:

- **A service** sees the contract of every api module it reaches, its own api's included, without a line.
- **A domain module's tests** see those of the build's api modules, their own api's included: most use its `<Context>Contract`. A peer published from another repository is asked for: `testImplementation(wow.contract(...))`.
- **Code that uses another context's generated code** asks for it: `implementation(wow.contract(project(":eshop-ordering-api")))`, `wow.contract("com.example:ordering-api:1.0.0")` or `wow.contract(libs.ordering.api)`. A module without a Wow plugin writes `implementation(project(":eshop-ordering-api")) { capabilities { requireFeature("contract") } }`; a Maven build adds the module's jar of classifier `contract`, of the same version.

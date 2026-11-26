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

With `wow-query-api` on the module's class path, each state gets `<State>Properties`: one `QueryField` constant per path a snapshot query can name, such as `ProductStateProperties.STATE__PRICE` for `state.price`, beside the system fields of `SnapshotProperties` ([Query Snapshots](./queries#write-a-query-in-kotlin)). A property holding elements in any collection shape, a list, a set, a `SortedSet`, a `Deque`, an array or a declared `Iterable`, gets its elements' fields too, named inside its `elementMatch { }`; a map is a leaf. A `Sequence` of a class is a compile error naming the property: Jackson reads its elements back as maps, so hold them in a `List` or a `Set`.

## The binding index

Each module gets one class implementing `ModelProvider`, registered for `ServiceLoader`, named after the module: `eshop-ordering-domain` gives `EshopOrderingDomainModelProvider`. It names the module's context and fragment, builds its models and its processors from `Dependencies`, and the host loads every one on its class path. It also records the binding it was generated for: a module built by a compiler of another Wow version is refused at startup, with a message that says to rebuild it, instead of failing on its first command. A test can load it to check what the module builds ([Test Domain Code](./testing#what-the-compiler-generated)).

## The Manifest fragment

The compiler writes the module's part of the service's contract to `META-INF/wow/manifest/<module>.json`, named by the module's project path with its segments as directories (`:eshop:ordering-domain` writes `eshop/ordering-domain.json`, `:eshop-ordering-domain` `eshop-ordering-domain.json`, the root project `.root.json`), so two projects never share one: the context, its aggregates with their commands, events, lifecycle commands, query models and schedules, its subscriptions with what each derives, schedules and calls, its published streams and its error codes. Its type schemas are references until the Gradle plugin completes them with the JSON Schemas Jackson's rules give, the query keywords included. [The Manifest and Compatibility](./manifest) covers what happens to it next.

## The contract code of the api module

The api module declares its domain module, `wowContract(project(":ordering-domain"))` under the plugin `me.ahoo.wow.api`, and the plugins select from the fragment what a peer needs, the commands, events, state schemas and errors of each context, as `META-INF/wow/contract.json`, the api module's contract. The contract also names the service that hosts the contexts, as the domain module's build declares it:

<!-- sample: fragment -->

```kotlin
wow {
    contractPackage = "me.ahoo.wow.example.eshop.ordering.api.order"
    service = "eshop-ordering-service"
}
```

A peer that depends on the api module finds the service by that name, and the service refuses to start under any other `spring.application.name`. The service's own Manifest takes its name from the contracts of the contexts it hosts, so the server module that hosts them does not repeat it; contracts that name different services fail its build, and with none it is named after the server's project path. The eShop's domain modules declare it the same way, as the ordering's `wow { service = "eshop-ordering-service" }` does.

An api module also compiles `<Context>Contract` from it, such as `EshopOrderingContract`, in the package the domain module's `contractPackage` names. Other services import it from there, so the package is the build's to declare, never derived from where the classes happen to be: it stays where it is whatever aggregates the context gains. A domain module with a context and no `contractPackage` fails `generateWowContract`, which prints the line to add, with the package the context's command and event classes share today. A segment that is a Kotlin keyword (`object`, `in`, `fun`, …) is quoted in the generated code. A `<State>View` is in the package its aggregate's command and event classes share, else in `contractPackage`.

A context's command and event classes are all in one api module, the one that declares its domain module in `wowContract`. A context whose classes are in another project of the domain module's class path, one that applies no `me.ahoo.wow.api` or does not declare the domain module, fails `generateWowContract`, which names the project and the lines to add to its build. A context whose classes are split between api modules fails the assembly of the contract, naming the classes that are not in the module: no peer would see a context whose contract no api module carries. So an api module that holds part of a context moves those classes to the one that carries it.


| Member | Holds |
| --- | --- |
| `NAME` | The context's name |
| `<Aggregate>.TYPE`, `<Aggregate>.ref(tenant, id)` | The aggregate's type, and a ref to one of its aggregates |
| `<Aggregate>.Errors.*` | Its error codes, by their full names |
| `<Aggregate>.STATE__*` | The query fields of its state, with `wow-query-api` |
| `Wait.*` | The hops of a wait chain in the context: `ACCEPTED`, `COMMITTED`, `SNAPSHOT`, `QUERYABLE`, and one per subscription its contract lists that handles a commit (not a saga's `@OnRejected` handler alone, whose rejection ended the wait), `<PROCESSOR>__<FUNCTION>` (`ORDER_PROCESS_SAGA__ON_ORDER_SUBMITTED`); the TypeScript generator's `<Context>Contract.Wait` gives the same hops |

A peer writes no path, name or code by hand: `EshopOrderingContract.Order.ref(tenant, id)` addresses an order, and `EshopOrderingContract.Order.Errors.WRONG_STATUS` compares a rejection ([Call Other Services](./calling-services), [Rejections and Error Codes](./rejections#what-the-caller-receives)). An aggregate's object is its name in Pascal case, and a name the context's object declares itself, `NAME`, `Errors` (the codes the context shares) or `Wait` (its hops), takes `Aggregate`: an aggregate `errors` is `ErrorsAggregate` and `wait` is `WaitAggregate`, as in the TypeScript contract.

The contract and this code are the api module's feature `contract`, published within the module as the jar of classifier `contract`; the module's own jar holds its types alone, so every module compiles against the types without waiting for any domain, the api's own domain module among them. The feature's capability is `<group>:<project name>-contract`, which a module of the same build asks for, and also `<group>:<artifactId>-contract` when the module is published under another name, which a build asking by coordinates asks for. Who sees the contract:

- **A service** sees the contract of every api module it reaches, its own api's included, without a line.
- **A domain module's tests** see those of the build's api modules, their own api's included: most use its `<Context>Contract`. A peer published from another repository is asked for: `testImplementation(wow.contract(...))`.
- **Code that uses another context's generated code** asks for it: `implementation(wow.contract(project(":eshop-ordering-api")))`, `wow.contract("com.example:ordering-api:1.0.0")` or `wow.contract(libs.ordering.api)`. A module without a Wow plugin writes `implementation(project(":eshop-ordering-api")) { capabilities { requireFeature("contract") } }`; a Maven build adds the module's jar of classifier `contract`, of the same version.

## Coverage

Every class the compiler and the contract plugins generate carries `@WowGenerated` (`me.ahoo.wow.api.annotation`, in `wow-annotations`, which `wow-api` brings): the model, access and query-field objects, the binding index, the object of each service route, the `<Context>Contract` and each object nested in it, the `<State>View` and each class it copies, and the file facades of the generated functions (`<Root>ModelKt`, `<Processor>SubscriptionsKt`). Its retention is binary: it is in the class files and needs nothing at run time. A coverage tool excludes generated code by it, not by names that also catch your own classes:

- **JaCoCo** skips a class annotated with anything of class or runtime retention whose simple name contains `Generated`, with no configuration. It judges each class file by its own annotations. The classes the Kotlin compiler synthesizes inside the generated functions, for their suspending lambdas and the copies of inline functions, carry none, so exclude those of the two facades by name: `*ModelKt$*` and `*SubscriptionsKt$*`. No hand-written class has those names.
- **Kover** excludes it with `annotatedBy`:

```kotlin
kover {
    reports {
        filters {
            excludes {
                annotatedBy("me.ahoo.wow.api.annotation.WowGenerated")
            }
        }
    }
}
```

  Observed with Kover 0.9.8: the filter also leaves out the synthetic classes of an annotated facade, so no name pattern is needed.

`@StateView` on a generated view is another thing: the read side finds the aggregate type by it, and a hand-written view carries it too. A generated view carries both.

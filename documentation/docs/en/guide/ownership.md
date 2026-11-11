---
title: Ownership and Access
description: Decide who an aggregate belongs to, give each owner one aggregate, declare the access attributes of a state, write the access policy that says which callers read and change which aggregates, and test it.
outline: deep
---

# Ownership and Access

Every aggregate belongs to its tenant. Most also belong to someone: an order to its buyer, a saved view to the person who saved it. Wow records that as the aggregate's **scope**, its owner and its space, which are the two **access attributes** every aggregate has. By default a caller reads and changes the aggregates of its own owner and space. A state may declare further access attributes, such as a department or the users a document is shared with, and an **access policy** of the service then decides, from the caller's claims, who reads and who changes each aggregate. This page chooses an aggregate's ownership, gives each owner one aggregate of a type, changes owners and spaces, declares access attributes, writes and tests a policy, and says where it applies. [Identity, Tenancy and Access](../concepts/identity) explains the model.

## Choose who an aggregate belongs to

`@AggregateRoot(ownership = ...)` declares it:

| `Ownership` | The aggregate belongs to | Such as |
| --- | --- | --- |
| `OWNER`, the default | Its tenant, and the owner and space of the command that creates it | An order, a basket, a saved view |
| `TENANT` | Its tenant, and a space if it has one; no owner is recorded | A product, a brand, a stock every caller of the tenant reads |

A product is read by every shopper and changed by staff, so it belongs to its tenant:

<!-- sample: examples/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/product/Product.kt#root -->

```kotlin
/**
 * A product of the catalog: a draft until it is published on the storefront, which takes a brand and a category; it
 * can be delisted and published again. Its name, description, brand, category and price change at any time.
 *
 * Every shopper of the tenant reads the products, and an employee changes them: a product belongs to its tenant
 * (`Ownership.TENANT`), so neither its reads nor its writes are restricted by an owner. The catalog
 * server's action-level authorization lets only an employee send its commands.
 */
@AggregateRoot(ownership = Ownership.TENANT)
@Description("A product of the catalog")
class Product(private val state: ProductState) {
```

The compiler writes the ownership into the Manifest, `"ownership": "tenant"` (an `OWNER` aggregate leaves it out, as the default), where query admission and the state routes read it. Changing it later changes who reads the aggregate, so the contract's compatibility check refuses it as a breaking change.

## What the kernel checks

An aggregate without an access policy of its own is changed by the **default rule**, its owner and space. The first command's caller gives an `OWNER` aggregate its scope. After that, a command whose caller's owner or space differs from the aggregate's is out of the caller's reach, and the kernel answers it as it answers a command on an aggregate that does not exist: `404 wow.aggregate.not-found`, with the same message, as a read of it is answered. The caller learns nothing of it: not that it exists, nor whether it is deleted, nor its version. A command that may create the aggregate is answered `403 wow.aggregate.forbidden` instead, since whether a creation succeeds tells anyway whether the aggregate exists. A part the caller has no value for is not checked:

- An **owner-level** caller, whose scope has an owner, reaches only its own aggregates.
- A **tenant-level** caller, without an owner, such as a member of staff, reaches every aggregate of its tenant, within its space when it acts in one. What it may do is decided by action-level authorization, such as CoSec's.

A `TENANT` aggregate records no owner, so its writes are not checked against one; the caller's space still is. Action-level authorization alone decides who may change a product: without an authorizer, any caller its route admits may send its commands ([Authorize commands](../operate/security#authorize-commands)). An aggregate with an access policy is checked by its policy instead ([Where a policy applies](#where-a-policy-applies)).

## Who reads it

Reads follow the same default rule. Query admission adds it to every query of the aggregate type, its event streams included, and the state routes check it on a state read and a trace:

- An `OWNER` aggregate is read within the caller's tenant, owner and space, each where the caller has one: an owner-level caller reads its own, a tenant-level one the whole tenant.
- A `TENANT` aggregate is read within the caller's tenant and space; the owner is never added. Every caller of the tenant reads a product, a shopper as much as a member of staff.

So `TENANT` does not restrict reads by role. An aggregate that belongs to its tenant but that only some callers may read, such as the failures an operator console lists or the sources of an analytics catalog, needs a read authorizer of the service's own: a `ReadAuthorizer` bean that refuses a caller without the role ([Authorize reads](../operate/security#authorize-reads)). Which of the aggregates a caller reads is an access policy's to decide ([Write an access policy](#write-an-access-policy)). Trusted code and the handlers of subscriptions read the whole tenant without a caller, and neither a read authorizer nor an access policy is asked for them.

## One aggregate per owner

Some aggregates exist once per owner: a buyer has one basket, a user one set of preferences. Such an aggregate takes its owner's id as its own, which `@AggregateRoot(idSource = AggregateIdSource.OWNER)` declares:

<!-- sample: fragment -->

```kotlin
@AggregateRoot(idSource = AggregateIdSource.OWNER)
class Basket(private val state: BasketState) {
    fun onCommand(command: AddBasketItem): BasketItemAdded =
        BasketItemAdded(command.productId, command.unitPrice, command.quantity)
}
```

Each owner then has exactly one basket in its tenant, and its id is the buyer's user id. The declaration requires `ownership = OWNER`, the default: an aggregate that belongs to its tenant has no owner to take its id from. Its owner never changes, so the default lifecycle leaves `TransferOwner` out, and the compiler refuses one listed in `lifecycle`, or a command that produces `OwnerTransferred`. The default, `AggregateIdSource.ASSIGNED`, takes the id the request names, or generates one for a creating command.

### Its routes

Every command of such an aggregate, its lifecycle commands included, has a route that names no id beside the route by id. The route without an id means the caller's own aggregate:

| Route | Serves |
| --- | --- |
| `POST /<context>/<aggregate>/<command>` | A command to the caller's own aggregate: `POST /eshop-basket/basket/add-basket-item` |
| `POST /<context>/<aggregate>/wow.lifecycle.<command>`, `DELETE /<context>/<aggregate>` | The lifecycle commands to the caller's own aggregate |
| `GET /<context>/<aggregate>/state`, `…/state/trace` | The caller's own aggregate's state and history |
| The routes by id: `POST /<context>/<aggregate>/{id}/<command>`, `GET …/{id}/state` | The aggregate of that id, for a caller with no owner |

No route generates an id: a creating command creates the aggregate of its owner.

### Who names the id

Admission takes the id from the caller:

- An **owner-level** caller's aggregate is its own: the id is its owner, on the routes without an id or by its own id, and a caller acting in another scope through `Wow-Scope` reaches that scope's owner's. Naming another owner's id reaches nothing: a creating command is refused with `403 wow.aggregate.forbidden`, any other is answered `404 wow.aggregate.not-found`, whether that owner's aggregate exists or not.
- A caller **with no owner**, such as a tenant-level member of staff, a service or a saga, has no aggregate of its own: it names the id, on the routes by id. On a route without an id it is answered `400 wow.command.malformed` ("This caller has no owner; address the aggregate by its id."), and `400 wow.query.malformed` on the state read. An unauthenticated caller is answered `401 wow.auth.unauthenticated`.
- A command a saga derives, or a scheduled command, that names no id takes the owner of the scope it carries: a saga that follows a buyer's order reaches that buyer's basket.

The kernel records the aggregate's id as its owner, whoever creates it, and a command whose owner is another does not reach it, a creating one included. The command functions therefore check no caller: the basket above needs no ownership check of its own.

## Check the caller in a command

Where neither the scope nor a policy is enough, a command function takes `Command<X>` and reads `command.scope`, the caller's owner and space as the aggregate's ownership keeps them (a tenant's aggregate sees no owner), and rejects the command with its own code ([Read the admitted command](./aggregates#read-the-admitted-command)).

## Commands a saga sends

A command a saga derives, a compensation and a scheduled command carry the scope and the operator of the commit they follow. An aggregate a saga creates therefore belongs to the same owner: a basket's checkout creates an order that belongs to the basket's buyer. An aggregate whose id is its owner's takes that id for its owner instead ([One aggregate per owner](#one-aggregate-per-owner)). Such commands are the service's own, so no access policy is asked for them ([What is trusted](#what-is-trusted)). [Write a Saga](./sagas#build-the-commands) covers them.

## Transfer an owner or a space

Two lifecycle commands change an aggregate's scope; every aggregate handles them unless `@AggregateRoot(lifecycle = [...])` leaves them out:

| Command | Route | Body |
| --- | --- | --- |
| `TransferOwner` | `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-owner` | `{"ownerId": "…"}` |
| `TransferSpace` | `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-space` | `{"spaceId": "…"}` |

A `TENANT` aggregate handles no `TransferOwner`, and neither does one whose id is its owner's. Every commit records the scope after it, so a projection or a query reads the current owner without replaying the history, and the aggregate's earlier event streams follow it ([Where a policy applies](#where-a-policy-applies)).

## Declare access attributes

An access attribute is a fact of the state that decides who may read and change the aggregate: a department, the users a document is shared with, the application a view was saved in. Mark a member of the state `@Access`. The view store keeps each saved view to the application it was saved in:

<!-- sample: platform/view-store/view-store-domain/src/main/kotlin/me/ahoo/wow/viewstore/domain/view/ViewState.kt#access -->

```kotlin
/**
 * The application it was created under: its creation's request fact `app_id`. An access attribute: each caller
 * reads the views of its own application.
 */
@Access
var appId: String = ""
    private set
```

- **What it marks.** A property of the state class, stored or computed, or a function of it without parameters, inherited ones included, neither private nor an extension.
- **A pure, cheap function of the state.** Every attribute is derived on each commit, so the member reads the state alone: no I/O, no clock, no random number. The compiler refuses one that suspends or is `@Blocking`.
- **Its value.** A `String`, an `Int`, a `Long`, a `Boolean` or an enum, nullable for none, or a value class that wraps one of them, published as the value it wraps; or a `List`, `Set` or `Collection` of them, neither the collection nor its elements nullable, for an attribute of several values. An enum is published as the name Jackson writes for its constant. Decimals, maps, arrays and nested collections are not attributes.
- **Its name.** `@Access("name")`, else the member's name. Lower camel case, a lower-case letter then letters and digits, at most 40 characters, unique within the aggregate ignoring case, and neither `owner` nor `space`, the built-in attributes every aggregate has.
- **How it changes.** Only as the state does. To grant access, write a domain command and its event (`ShareDocument`, `DocumentShared`), which the domain checks and the log records. No lifecycle command sets an attribute.

The compiler writes each attribute into the Manifest's `access` section of its aggregate (its name, its type, `string`, `integer` or `boolean`, and whether it holds several values; [Manifest](../reference/manifest)), and generates `<State>Access` beside the state: an object with a typed reference to each attribute, named in upper snake case after it (`ViewStateAccess.APP_ID` for `appId`), and to the built-in `OWNER` and `SPACE`. Removing, renaming or retyping an attribute, or changing whether it holds several values, is a breaking change of the contract; adding one is not. A model written as code declares each with `access(attribute) { state -> … }`.

Every snapshot and every event stream records its aggregate's attributes as `access`, which a query names as `access.<name>` ([Query Language](../reference/query-language)), and the OpenAPI document and the generated TypeScript clients type it by the attributes the aggregate declares.

## Write an access policy

An access policy says, for a caller, which aggregates of one type it may read and which it may change. Declare one per aggregate as a bean of type `AccessPolicy<S>` of its state, built by `accessPolicy { … }`. The view store's: a caller reads the views, preferences and dataset wordings of its own application, in its own owner and space, and changes those of its owner and space:

<!-- sample: platform/view-store/view-store-server/src/main/kotlin/me/ahoo/wow/viewstore/server/ViewStoreConfiguration.kt#access -->

```kotlin
@Bean
fun viewAccess(): AccessPolicy<ViewState> = accessPolicy {
    read { caller -> allOf(ViewStateAccess.APP_ID eq caller.app, ownerAndSpace(caller)) }
    write { caller -> ownerAndSpace(caller) }
}

@Bean
fun preferencesAccess(): AccessPolicy<ViewPreferencesState> = accessPolicy {
    read { caller -> allOf(ViewPreferencesStateAccess.APP_ID eq caller.app, ownerAndSpace(caller)) }
    write { caller -> ownerAndSpace(caller) }
}

@Bean
fun wordingAccess(): AccessPolicy<DatasetWordingState> = accessPolicy {
    read { caller ->
        allOf(
            DatasetWordingStateAccess.APP_ID eq caller.app,
            ownerAndSpace(caller)
        )
    }
    write { caller -> ownerAndSpace(caller) }
}
```

- **`read` and `write`.** A policy gives both: `read { caller -> … }` says what the caller may read, `write { caller -> … }` what it may change, and `readWrite { caller -> … }` gives one predicate for both. Nothing is granted by omission, so a policy without a `write` is refused when it is built: `write { denyAll() }` makes an aggregate no caller changes explicit.
- **It replaces the default rule.** The caller's owner and space are compared only where the policy compares them. `ownerAndSpace(caller)` is the default rule, to combine with the policy's own comparisons. It compares as the aggregate's ownership keeps the scope: an aggregate that belongs to its tenant records no owner, so it compares the space alone, as for the wording above.
- **Several aggregates of one state.** A policy names the one it governs, `accessPolicy(aggregate = …)`, when several aggregates of the service share its state type.

A predicate compares the aggregate's attributes, through the references of `<State>Access`, with the caller's claims:

| Predicate | Holds when |
| --- | --- |
| `A eq value`, `A isIn values` | The single attribute `A` is the value, or one of the values |
| `A startsWith prefix` | The single string attribute `A` starts with the prefix: a level of a materialized path, such as `/acme/sales` |
| `A.isNull()` | The aggregate has no value of the single attribute `A` |
| `M contains value`, `M containsAny values` | The values of the multiple attribute `M` hold the value, or one of the values |
| `M.isEmpty()` | The multiple attribute `M` holds no value |
| `allOf(…)`, `anyOf(…)`, `not(p)` | Each predicate holds (always, of none), one holds (never, of none), `p` does not hold of a value the aggregate has |
| `allowIf(condition)` | Every aggregate when the condition on the caller alone holds, such as a role; none otherwise |
| `allowAll()`, `denyAll()` | Every aggregate, public data said explicitly; none |

The caller is the one the host resolved for the request ([Authenticate callers](../operate/security#authenticate-callers)). A policy reads `caller.subject`, `caller.app` and `caller.scope`, and its claims by name: `caller.claim("org")` is a claim's first value or null, `caller.claims("departments")` its values or none, and `caller.hasRole("auditor")` whether its `roles` claim holds the role.

**What is missing denies.** A comparison with a claim the caller does not hold, null or no values, holds neither way, and so does a comparison of a value the aggregate does not have. `not` holds only of a value the aggregate has, so neither an absent value nor a missing claim ever grants, however the predicate negates it. Public data is said in the policy: `allowAll()`, or `A.isNull()` within an `anyOf`.

A policy of documents that their department reads and changes, shared with some users, owned by one and audited by a role:

<!-- sample: test/test-dsl/src/test/kotlin/me/ahoo/wow/test/AccessPolicySpecTest.kt#policy -->

```kotlin
private val documentAccess = accessPolicy<Document> {
    read { caller ->
        anyOf(
            DocumentAccess.DEPARTMENT isIn caller.claims("departments"),
            DocumentAccess.SHARED_WITH contains caller.subject,
            DocumentAccess.OWNER eq caller.subject,
            allowIf(caller.hasRole("auditor")),
        )
    }
    write { caller -> DocumentAccess.DEPARTMENT isIn caller.claims("departments") }
}
```

### Default deny and the startup checks

An aggregate whose state declares access attributes is read and changed only through its policy: a node that has such an aggregate without a policy refuses to start, naming it and its attributes. An aggregate that declares none keeps the default rule, and may still have a policy, which then compares its owner and space and the caller's claims alone.

The node resolves its policies once, at startup, against the Manifest, and refuses to start on:

- a policy of a state that no aggregate of the service has;
- a policy of a state several aggregates share, without naming the one it governs, or naming an aggregate whose state it is not;
- two policies of one aggregate.

A node that hosts only some of the service's contexts leaves out the policies of the aggregates other nodes host. A policy's references are typed by its state, so a comparison of another state's attribute does not compile; one its aggregate does not declare, of a policy built against another version of the state, matches nothing, and the node logs it.

## Where a policy applies

A policy decides every read and every command of its aggregate, in place of the default rule; an aggregate without one is decided by the default rule on the same paths.

**Reads.** Every read is admitted the same way:

- **Snapshot queries**, a list, a page, a count, an aggregation and their streams, take the policy's `read` as a filter the store applies ([Query Snapshots](./queries#who-sees-what)).
- **A state read**, at a version or an instant, and **a trace** are decided on the aggregate's latest state, so a caller reads the whole history of an aggregate it may read now, and none of one it may not. A hidden aggregate answers `404 wow.aggregate.not-found`, exactly as a missing one does ([Read State](./reading-state#who-sees-a-state)).
- **Event queries** are decided on what each stream records of its aggregate's current attributes. When a commit changes them, the built-in subscription `wow.access` stamps the new ones over the aggregate's earlier streams, normally within seconds, so a transfer or a revoked share applies to the whole history. A stream not yet stamped shows to no caller under a policy ([Query Event Streams](./event-queries#who-sees-what), [Rebuild and Replay](../operate/replay#access-attributes)).
- **A dataset** bound to the aggregate reads each row by its aggregate's current attributes ([Publish Datasets](./datasets#rows-of-an-aggregate-with-an-access-policy)).

A read authorizer is asked first, whether the caller may read the model at all ([Authorize reads](../operate/security#authorize-reads)); the policy then decides which aggregates it reads, and the state schema's masks which fields it sees of them ([Describe what types cannot](./queries#describe-what-types-cannot)).

**Commands.** The kernel evaluates the policy on the aggregate's current attributes, its owner and space included, which it already holds:

- **Before deciding,** a command runs when `write` lets the caller change the aggregate. A caller it refuses is answered `404 wow.aggregate.not-found` when `read` hides the aggregate from it too, and `403 wow.aggregate.forbidden` when it may read it. A creating command is answered `403`, since whether it succeeds tells anyway whether the aggregate exists.
- **After deciding,** a creation, and a decision that changes the aggregate's declared attributes, is checked again on the aggregate it leaves: refused with `403` without appending when the caller could not change the result, so no caller creates an aggregate, or moves one, out of its own reach. A transfer of the owner or the space is checked before deciding only, since it gives the aggregate away on purpose.

A command authorizer, such as CoSec's, still decides whether the caller may send the command at all ([Authorize commands](../operate/security#authorize-commands)).

## What is trusted

The inside of the platform is trusted: the internal port, the cluster channel, the command topics and the stores are reachable by the platform's own services alone, and callers come in through the public port ([The internal port](../concepts/identity#the-internal-port)). So no access policy is asked for what the service does itself:

- **Derived commands.** A command a saga, a timer, a compensation or a synchronous call derives carries the causation of the commit it follows and the scope of the root command, which admission authorized when it arrived. It reaches every aggregate.
- **A service acting as itself.** A service's identity (`Caller.ofService(...)`) reads and sends commands without a policy. Another service that calls or reads this one on its internal port is bounded by what this one's caller list lets it (`wow.callers.<service>.commands` and `reads`; [Close the internal port](../operate/security#close-the-internal-port)). Code of the service sends as the service with `commandClient.of(Caller.ofService("<wow.service>"))`; the in-process client's default caller is anonymous, holds no claim, and is refused by a policy.
- **Trusted code.** Code that reads with no caller (`NodeQueryApi.trusted()`) and the handlers of subscriptions read the whole tenant.

A command that carries neither a causation nor a caller, such as one a producer wrote on a command topic without one, is refused under a policy ([Restrict the command topics](../operate/security#restrict-the-command-topics)). A caller allowed to act in another scope changes where it acts, not what the policy grants: a support role that must read every aggregate gets a rule of its own (`allowIf(caller.hasRole("support"))`).

## Test a policy

`accessPolicySpec(model, policy)` of the test DSL checks a policy as the kernel and the queries evaluate it: given the aggregate after its events, `given(…)`, or in a state, `givenState(state, scope)`, it checks what each caller may do. `whenCaller(subject, claims, roles)` names a caller, and `expectRead()`, `expectNoRead()`, `expectWrite()` and `expectNoWrite()` check it; the built-in owner and space are the scope the aggregate is given in:

<!-- sample: test/test-dsl/src/test/kotlin/me/ahoo/wow/test/AccessPolicySpecTest.kt#spec -->

```kotlin
@Test
fun `a caller reads and changes what the policy grants on the aggregate its events leave`() =
    accessPolicySpec(model, documentAccess) {
        given(Created("sales"), Shared("bob"))
        whenCaller(subject = "bob", claims = mapOf("departments" to listOf("hr"))) {
            expectRead()
            expectNoWrite()
        }
        whenCaller(subject = "carol", claims = mapOf("departments" to listOf("sales"))) {
            expectRead()
            expectWrite()
        }
        whenCaller(subject = "dave") {
            expectNoRead()
            expectNoWrite()
        }
        whenCaller(subject = "erin", roles = listOf("auditor")) { expectRead() }
    }
```

The model is the one `aggregateSpec` takes, and the policy the bean function's. A check also fails when the policy compares an attribute the model does not declare. In an `aggregateSpec`, `expectAccess(mapOf(…))` checks the attributes a command leaves the aggregate with ([Test Domain Code](./testing#expect)).

## A fixed tenant

An aggregate type whose aggregates all live in one tenant, whatever the caller's, takes it from the host: `wow.aggregates.[<context>.<aggregate>].tenant`.

## Act in another scope

A caller allowed to act in another tenant or scope, such as a support tool, names it in the `Wow-Scope` header, or in the command client's `CommandRequest` (`targetTenant`, `targetScope`). The commit records that the operator acted in another scope. An access policy still decides what it reads and changes there ([What is trusted](#what-is-trusted)).

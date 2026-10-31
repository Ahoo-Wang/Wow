# The model: aggregates, commands, events, state

The job: put a business rule where it holds. An aggregate decides each command from the command and its current state; the events it decides are the only thing written; the state is evolved from them. Verify every annotation and signature below in the target's version (`me.ahoo.wow.api.*`), and the generated code in its `build/generated/ksp`.

## One aggregate, whole

The quickstart's account: a state evolved by `onSourcing`, a root whose `onCommand` functions decide, and the rejection codes it answers with. A deposit's positive amount is no rejection: it is `@field:Positive` on the `Deposit` class, checked at admission.

<!-- sample: examples/quickstart/src/main/kotlin/me/ahoo/wow/example/quickstart/Account.kt -->

```kotlin
package me.ahoo.wow.example.quickstart

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.api.decide.reject

class AccountState {
    var owner: String = ""
        private set
    var balance: Long = 0
        private set

    fun onSourcing(event: AccountOpened) {
        owner = event.owner
    }

    fun onSourcing(event: Deposited) {
        balance += event.amount
    }

    fun onSourcing(event: Withdrawn) {
        balance -= event.amount
    }
}

@AggregateRoot
class Account(private val state: AccountState) {
    fun onCommand(command: OpenAccount): AccountOpened = AccountOpened(command.owner)

    fun onCommand(command: Deposit): Deposited = Deposited(command.amount)

    fun onCommand(command: Withdraw): Withdrawn {
        if (command.amount > state.balance) {
            reject(Rejections.INSUFFICIENT_BALANCE, "The balance is ${state.balance}.")
        }
        return Withdrawn(command.amount)
    }

    /** The codes it rejects with, by local name: the Manifest and its answers say `bank.account.insufficient-balance`. */
    object Rejections {
        @ErrorCode(description = "The balance does not cover the withdrawal.", status = 409)
        const val INSUFFICIENT_BALANCE = "insufficient-balance"
    }
}
```

## Names

- Context: `@BoundedContext(name, description)` on an object of the domain module; names match `[a-z][a-z0-9-]*` (no dot: it separates type names). The compiler fails on a name that breaks it. KSP option `wow.context` names the context when a module has no single `@BoundedContext`.
- Aggregate: `@Name` or the root's simple name in `snake_case`. Types: `<context>.<aggregate>.<Name>`. Subscriptions: `<processor>.<function>`.

## Commands and decisions

- `@AggregateRoot class Root(private val state: State)` (or a root that is its own state). Attributes: `lifecycle` (default all five lifecycle commands), `published`, `publishState`, `topic`, `retiredEvents`.
- Command functions: non-private, `@OnCommand` or named `onCommand`. First parameter the body `X` or `Command<X>` (`ref`, `scope`, `headers`, `causation`); then services (factory parameters bound once from beans; `@Name` qualifies by bean name) and `DecideScope`.
- Return the event type, in this order of preference: the event (`Withdrawn`), `E?` when the command may change nothing (null is a no-op), a sealed type of the events, or a list of one event type; reject by throwing (`reject(code, message)`). Return `Decision` only when the type cannot name the events (two kinds without a common sealed type, a domain event and a lifecycle event), with `@OnCommand(produces = [...])` listing them; `produces` is forbidden when the return type names them. A comment on such a function says why it is a `Decision`.
- The kernel holds each function to the events its return type or `produces` names: another event fails the command, `wow.command.undeclared-event` (500), a bug in the model.
- Creation: `@CreateAggregate` on the command (the aggregate must not exist, else `wow.aggregate.exists`), `@AllowCreate` (either), neither (it must exist, else `wow.aggregate.not-found`).
- `@Unaddressed`: a command whose request may name no aggregate because a `CommandTransformer` of the service finds it (a derived id).
- **No tenant, owner or version in command bodies**: they come from the caller and `If-Match`. The aggregate's id comes from the route or the saga's ref, unless the body names it:
  - `@AggregateId` (`me.ahoo.wow.api.annotation`) on one `String` property of the command (or of an interface it implements), stored or computed (`val stockId: String get() = "$warehouseId-$sku"`), public or internal, written by Wow's JSON (not `@JsonIgnore`d). A command that may create its aggregate may declare `String?`: null leaves the id to the server.
  - The command's route is then `POST /<ctx>/<agg>/<command>` alone; a saga sends `CommandRequest.of(body)`, and a command of another context goes to its topic keyed by the aggregate. A blank id, or a request naming another aggregate beside the body, is `wow.command.malformed` (400); the same one is accepted.
  - The compiler refuses it on an `@Unaddressed` or mounted command and on an aggregate whose id is its owner's; a `CommandTransformer` taking such a command stops the service at start. Use it instead of a transformer when the id is in the command's own fields; keep `@Unaddressed` and a transformer for an id found elsewhere (a lookup, the caller).
- **A command's shape is a Jakarta constraint on its class**, not a check in decide: `@field:Positive`, `@field:PositiveOrZero`, `@field:NotBlank`, `@field:NotEmpty`, `@field:Valid` for nested values (`jakarta.validation:jakarta.validation-api` in the api module, a provider such as `spring-boot-starter-validation` in the service). The gateway checks every command it takes (HTTP, `CommandClient`, sagas, other contexts) and answers `wow.command.invalid` (400) with `violations` (path, constraint simple name, message). Decide keeps the rules that need the state or the caller.
- **A decide function that blocks its thread** (JDBC, a blocking client) is marked `@Blocking`, so it runs on a virtual thread instead of the shared worker; otherwise make it `suspend` or return a Reactor type (`wow-reactor`). `@Blocking` is a compile error on evolve, `@Upgrade` and `@Tags` members, which do no I/O.
- **A decide function that queries** takes the node's read API as a service, `QueryApi` or `NodeQueryApi` (`me.ahoo.wow.api.read`, in `wow-api`); the host gives the model the node's own. Prefer the fact in the command (a `CommandTransformer`, the sending saga): a read reruns on every retry after a version conflict.
  - As the command's caller, rebuilt: `queries.of(Caller.of(scope.command.ref.tenantId, scope.command.scope, scope.command.operator?.id))` reads within its tenant, owner and space. It has no roles, policies or ABAC attributes (they live only with the request; a command may be decided on another node or from its topic), so a policy that reads them sees none, and a role-based one refuses the read.
  - For a read no policy should restrict: a `NodeQueryApi` and `trusted()`. **It reads every tenant**: filter by `tenantId(scope.command.ref.tenantId)` yourself in a service of several tenants.
  - A read that fails, one the read side refuses, or one the code gets wrong (a state class no aggregate has) fails the command, `wow.command.failed` (500), not `wow.command.invalid`. In an aggregate spec, pass `memoryQueries { snapshots(...) }` (`wow-test-query`) to the model factory as the `QueryApi`.
- **Decide never mutates state**; the test DSL fails a spec that does. A decide function is deterministic given the command, the state and its services' answers.
- `@AfterCommand(include, exclude)` or `afterCommand`: returns extra events in the same stream; it cannot reject.

## Rejections and error codes

- Reject with `reject(code, message)` (`me.ahoo.wow.api.decide`; it throws a `RejectedException`, which the kernel answers as the rejection), or `Decision.reject(code, message)` from a function returning `Decision`; Java throws `RejectedException(Rejection.of(code, message))`. `require` answers `wow.command.invalid`, `check` `wow.command.refused`: exactly those classes, not a subclass. Any other exception thrown by decide is a failure (`wow.command.failed`), answered as 500, the read API's errors included.
- Codes: `@ErrorCode(description, status = 422)` on a `const val String` in an `object` nested in the `@AggregateRoot` class, by local name (`const val EMPTY = "empty"`); the compiler and the kernel name it `<context>.<aggregate>.empty`. A code with a dot is full (`<context>.<code>` for a context-wide one). Never the `wow.` prefix; each code once per module. Peers compare with the api's generated `<Context>Contract.<Aggregate>.Errors.<NAME>`.
- A code is for a rule that needs the state or the caller (not the owner, not found, empty, insufficient); a rule about the command alone is a constraint on its class, with no code.
- Choose the status for the caller: 422 (the default) for a rule the request breaks, 409 for a conflict with the current state. The framework's own codes are in `FrameworkCodes` (`me.ahoo.wow.api.result`); a domain does not reject with them.

## DecideScope

`DecideScope` is the decide function's view of the request: `command`, `ids` (deterministic ids), `putResult`, `reserve(namespace, key[, tenancy])` (`suspend`) and `release(...)` for unique keys, `onAppendFailure`, `afterAppend`, `schedule(key, rule, command, target)` and `cancelSchedule(key)`.

- **Unique keys** (a user name, an order number): `reserve` in decide; the reservation commits with the events and is released if the append fails. `tenancy` is `TENANT` (the default: unique within the tenant) or `SYSTEM` (unique across tenants). Every unique key includes the tenant unless it is meant to be global.
- **Scheduled commands**: `schedule` / `cancelSchedule` in decide (`@OnCommand(schedules = [Command::class])` declares them); at least once, never early; a rejection when due is the normal guard.

## State and evolution

- Evolve: on the state, `@OnSourcing` or `onSourcing`, one per produced event, even a no-op. Mutable in place by default, or returning the state type for an immutable state. No services, no I/O, no clock.
- Every property initialized: the state is serialized as it is, and its schema fingerprint is in the Manifest.
- Owner and space: the caller's scope places a new aggregate; the lifecycle commands move it (`TransferOwner`, `TransferSpace`).
- Tags, which ABAC query policies filter by: `@Tags` on one member of the state, returning `Map<String, Collection<String>>`, pure. It is evaluated after every commit and on load, and each key it returns replaces that key's earlier values (`ApplyTags` sets the others, never these). Return every key it owns on every call, with an empty list for none: a key it stops returning keeps its last value.

## Changing events after release

Events are permanent. Change one only so that every stored version still reads:

- `@Revision(n)` on the event class, and `@Upgrade(event = X::class, from = n)` on a top-level or `object` function taking and returning `JsonNode`/`ObjectNode`, a complete chain 1..n-1. A stored event is read at its revision and upgraded on load.
- `@Alias` for a renamed event or command.
- `@AggregateRoot(retiredEvents = [...])` for an event no longer produced: stored ones are skipped on load, their streams still a version.
- The module's `check` compares the Manifest with its baseline and fails a break (an event's schema changed without a revision, a removed event without retiring it). Update the baseline only for an intended, reviewed change.

## Java domains

A module of Java sources applies `kotlin("jvm")` beside `id("me.ahoo.wow")` (the generated models are Kotlin). Java decide functions return an event or a `Decision` from `Decision.events(…)`, `Decision.reject(code, message)`, `Decision.NoOp.INSTANCE`; `@ErrorCode` goes on a `public static final String` field. Java cannot call the `suspend` APIs (`DecideScope.reserve`, `SagaContext.call`), and the test DSL is Kotlin only. State and event classes need public getters or record components.

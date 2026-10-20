---
title: Deciding and Evolving
description: The five kinds of function in a Wow domain model, why deciding is separate from evolving, and how the compiler binds each function to the runtime.
---

# Deciding and Evolving

A Wow domain model is made of plain classes and functions. This page explains which functions there are, what each may and may not do, and how Wow calls them. It describes the model, not the annotations one by one.

## Five kinds of function

| Function | Gets | Returns | May do I/O | When it fails |
| --- | --- | --- | --- | --- |
| Decide | the current state and a command | a decision: events, no change, or a rejection | Yes, through dependencies bound when the model is built | The command is rejected or fails; nothing is written. |
| Evolve | the state and one event | nothing (it changes the state), or a new state | **No** | It is a defect in the model, and it fails fast. |
| Project | a committed change and the state after it | nothing | Yes; it makes its writes idempotent | The aggregate is parked for that projection. |
| React | a committed change and the state after it | nothing | Yes; it makes its effects idempotent | The aggregate is parked for that reactor. |
| Saga | a committed change and the state after it | commands to send | Yes | The aggregate is parked, unless the saga declared that the rejection it got is expected. |

Decide and evolve belong to an aggregate. Project, react and saga functions belong to processors, which subscribe to the log; [Subscriptions, failures and replay](./subscriptions) explains how they run.

## Decide, then evolve

Deciding and evolving are separate functions because they answer different questions.

- **Decide** answers "given what is true now, what happens?" It reads the state, checks the rules, may call a domain service, and produces events. It is the only place a business rule lives.
- **Evolve** answers "given that this happened, what is true now?" It applies one event to the state. Because events are kept forever and replayed whenever the state is rebuilt, evolve must give the same result every time it sees the same events. So it does no I/O and has no dependencies.

The split has consequences you can rely on. Replaying the log rebuilds exactly the state the aggregate had. A conflict retry runs decide again on the fresh state, and nothing else repeats. A test states its expectations as events and state, the same terms the model is written in.

A decision has three outcomes, and all three are values, not exceptions:

- **Events**: one or more events to append, in order.
- **No change**: the command is accepted, and nothing is appended.
- **Rejected**: a rejection with an error code, a message and, optionally, field violations. The code is declared on the model and listed in the Manifest, so clients and other services know it ahead of time.

There is no error handler on an aggregate. A rejection is the branch of decide that returns one. A compensating action after a failure belongs in a saga.

## What decide can reach

Decide may do I/O, but it reaches the world only in ways the kernel can account for:

- **Domain services** are parameters of the command function. The compiler turns them into parameters of the generated model factory, and the host binds them once, when it builds the model. Nothing is looked up per command.
- **The decide scope** is the command's own context. Through it, decide can reserve a unique key across aggregates, register a compensation that runs if the append does not succeed, schedule a command for later, and return an extra result to the caller.
- **The command** is either the body alone or the admitted command, which also carries what admission established: the aggregate it addresses, the caller's scope and the request facts.

Identity never comes from the command body. The aggregate a command addresses is decided at admission, from the route or the message, and the owner and space come from the authenticated caller. [Identity, tenancy and access](./identity) explains why.

### After hooks

An after hook runs after the command function, on the same state, and may add events to the same decision. It is part of decide: its events are evolved and appended together with the command function's, and if it fails, the whole command fails and nothing is written. Use it for an event that a group of commands all add.

### Lifecycle commands

Every aggregate understands a few lifecycle commands without code: delete, restore, transfer the owner, transfer the space and apply tags. The kernel provides their decide and evolve, and they change the aggregate's metadata, which the snapshot carries. An aggregate handles all of them unless it lists fewer. Setting and cancelling a schedule are system commands of every aggregate in the same way.

## Mutable or immutable state

An evolve function can be written two ways:

- **Mutable**, the default: evolve receives the state and changes its fields. Nested collections are updated in place.
- **Immutable**: evolve receives the state and the event and returns a new state.

Both are safe because the kernel, not the state class, provides the guarantees immutability usually gives:

| Guarantee | How the kernel provides it with mutable state |
| --- | --- |
| A failure does not leave a half-changed state | A state is taken out of the cache for each attempt and put back only when the attempt commits. A failed attempt discards the instance, and the next command loads the state again. |
| No two commands change one state at once | Each aggregate has one lane: its commands run one at a time, in arrival order. |
| Each version has a stable view | When a version commits, the kernel serializes the state once. Snapshots and subscriptions get that view, never the live instance. |

The price of mutable state is that nothing stops decide from changing a field directly. Such a change does not go through an event, so the state in memory drifts away from the log until the next reload. Change state only in evolve.

## Two ways to write it

The same model can be written with annotations on classes or with the Decider DSL in Kotlin. Both compile to the same aggregate model: a table of command functions, a table of evolve functions, identity rules, creation rules and guards. With annotations, the compiler generates the binding code at build time; at run time nothing is scanned and nothing is called by reflection.

Java code uses the same annotations. The compiler reads Java sources as well as Kotlin ones.

## How a function is called

Many functions are pure computation: a command decided from the state alone, a projection that copies fields into memory. They should not pay for coroutines. So the compiler picks one of three call shapes for each function, from its signature, and the runtime makes no decision per call:

| Shape | Recognized by | How it is called |
| --- | --- | --- |
| Plain | an ordinary function | Inline, on the aggregate's lane, with no suspension and no thread switch |
| Suspending | `suspend` | In the current coroutine; it gives up the thread only when it actually suspends |
| Reactor | returns `Mono`, `Flux` or `Publisher` | Through the Reactor adapter module |

A plain function is assumed not to block. One that does blocking I/O, such as a projection that writes with JDBC, is marked `@Blocking`, and the runtime runs it on a virtual thread instead. A blocking function without the mark holds up every other aggregate on the same thread.

The kernel itself suspends only at real I/O. When the state is cached and decide is plain, a command runs from its lane to the append in straight-line code, and the append is the only point where it waits.

## Events change over time

The log is kept forever, while event classes change. Reading old events is therefore a normal part of the model:

- An event's identity is its **name**, `context.aggregate.EventName`, not its class. Renaming or moving a class does not affect history. A former name stays readable as an alias.
- An event declares a **revision**. When its structure changes, you write an upgrade function from the previous revision to the next, and the compiler checks that the chain from revision 1 to the current one has no gap.
- An event type that no longer has a class is declared **retired** on its aggregate. Reading skips it: replay ignores it, and subscriptions move past it.

These are changes to your model, not to Wow's formats. A change to Wow's own storage or wire formats happens only between major versions, in the migration layer.

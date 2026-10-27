---
layout: home
kaicodeAward: true
title: Domain Model as a Service
hero:
  name: "Wow"
  text: "Domain Model as a Service"
  tagline: "Write only the domain model, and get a system you can run in production."
  image:
    src: /images/logo.svg
    alt: Wow
  actions:
    - theme: brand
      text: Quick Start
      link: /start/quickstart
    - theme: alt
      text: How Wow Works
      link: /concepts/
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/Wow
features:
- title: Only the domain model
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z"/></svg>'
  details: "Commands decide, events evolve the state, and the compiler binds both at build time: no reflection and no infrastructure code."
  link: /concepts/programming-model
- title: The log is the bus
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 6h13"/><path d="M8 12h13"/><path d="M8 18h13"/><path d="M3 6h.01"/><path d="M3 12h.01"/><path d="M3 18h.01"/></svg>'
  details: "One append is the only write. Everything else reads the log from its own checkpoint, so no committed fact is lost on the way."
  link: /concepts/event-log
- title: One contract
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>'
  details: "The Manifest describes the service once. HTTP routes, OpenAPI, the TypeScript client and the views are derived from it."
  link: /concepts/contract
- title: Read models replay
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/></svg>'
  details: "The latest state of every aggregate is queryable as it is, and every read model can be rebuilt from the start of the log."
  link: /concepts/read-models
- title: Failures are data
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 3h6l3 7-6 11-6-11 3-7Z"/><path d="M9 3 3 10h18L15 3"/></svg>'
  details: "A handler that keeps failing parks the one aggregate it failed on. Nothing is skipped, and nothing else stops."
  link: /concepts/subscriptions
- title: PostgreSQL or MongoDB
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v14a9 3 0 0 0 18 0V5"/><path d="M3 12a9 3 0 0 0 18 0"/></svg>'
  details: "Both are first-class log backends. Kafka carries what crosses contexts, and ClickHouse serves the datasets."
  link: /concepts/event-log#backends
---

## What you write, and what you get

A command function of the quickstart's bank account decides a withdrawal against the current state:

<!-- sample: example/quickstart/src/main/kotlin/me/ahoo/wow/example/quickstart/Account.kt#withdraw -->

```kotlin
fun onCommand(command: Withdraw): Withdrawn {
    if (command.amount > state.balance) {
        reject(Rejections.INSUFFICIENT_BALANCE, "The balance is ${state.balance}.")
    }
    return Withdrawn(command.amount)
}
```

Its test says it in the same terms, given events, when a command, expect events and state, with no server:

<!-- sample: example/quickstart/src/test/kotlin/me/ahoo/wow/example/quickstart/AccountSpec.kt#withdraw -->

```kotlin
@Test
fun `a withdrawal the balance does not cover is rejected`() = aggregateSpec(model, id = "alice") {
    given(AccountOpened("Alice"), Deposited(100))
    whenCommand(Withdraw(150)) { expectRejected(Account.Rejections.INSUFFICIENT_BALANCE) }
    whenCommand(Withdraw(30)) {
        expectEvents(Withdrawn(30))
        expectState { balance.assert().isEqualTo(70) }
    }
}
```

From that model, without more code, the service gets an HTTP route for each command, a state read and snapshot queries, an OpenAPI document, a Manifest from which `wow-generator` generates a typed TypeScript client, and its rejections as `application/problem+json` with their error codes.

## Start where your job is

| You want to | Start with |
| --- | --- |
| Decide whether Wow fits your system | [How Wow works](/concepts/), then the [glossary](/reference/glossary) |
| Write a service | [Quick start](/start/quickstart), then [deciding and evolving](/concepts/programming-model) |
| Call a service from a TypeScript front end | [TypeScript client](/typescript/) |
| Run services in production | [Identity, tenancy and access](/concepts/identity), then [subscriptions, failures and replay](/concepts/subscriptions) |
| Analyze the data | [Read models](/concepts/read-models), then the [workbench](/data/workbench) |

## Recognition

Wow received the [KaiCode’26 Excellent Award](https://www.kaicode.org/2026.html). The official results highlighted its modular DDD/CQRS design, disciplined multi-reviewer code review, Testcontainers-based integration testing, enforced test coverage thresholds, Detekt static analysis, bilingual documentation, and long history of semantically versioned releases on Maven Central.

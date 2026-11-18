---
title: Troubleshooting
description: Symptoms, their causes and what to do; a node that will not start, commands that time out or are refused, subscriptions that park or fall behind, partitions without an owner.
outline: deep
---

# Troubleshooting

Each section starts from what you see, names the usual causes, and says what to do. Every answer a node gives carries a code; [Error Codes](../reference/error-codes) lists them all.

## A node will not start

The host refuses to start rather than run with a setting that would quietly do the wrong thing. The log names the cause:

| The log says | Cause | Do |
| --- | --- | --- |
| No durable backend | No `DataSource` or `MongoClient` bean, outside a development profile and with an internal port that binds more than loopback | Set `spring.datasource.url` (with HikariCP on the class path) or `spring.mongodb.uri`, give the application its store bean, or set `wow.backend: memory` on purpose |
| The service's name is no name its nodes can state on the internal port | `wow.service`, or `spring.application.name` without it, holds a character other than letters, digits, `.`, `_` and `-`, starts with a character other than a letter or a digit, or is longer than 253 | Rename the service, the same on every node and in the peers' `wow.callers` |
| Unknown Wow configuration keys | A mistyped `wow.*` key, a key of an earlier version, or a key of a module that is not on the class path | Fix the key, take today's key from the migration guide's configuration page the message links, or add the module ([Configure a Service](./configuration#keys-are-checked)) |
| A subscription, aggregate or context the node does not serve | A name under `wow.subscriptions`, `wow.aggregates` or `wow.contexts` that matches nothing | Fix the name, or remove the entry |
| No command transport | A saga sends commands to another context and the node has neither Kafka nor in-memory topics | Set `wow.kafka.bootstrap-servers`, or run it in development, where its topics are in its JVM (`wow.transport`) |
| A missing topic, or a command topic with the wrong number of partitions | `wow.kafka.ddl: validate`, and the operator has not created the topic as the log needs it | Create the topic: a command topic has exactly `wow.log.partitions` partitions ([Kafka](./kafka#creating-topics)) |
| A structure of another format | A table recorded in a format this version of Wow does not read | Open it with the version of Wow that wrote it, or start from an empty store; never edit `wow_format` |
| The contract and the types of its api module are of different versions | A context's contract lists classes of which the class path holds only some: a contract of one version beside types of another, as a Maven build resolving the two apart can take | Depend on one version of the api module, its jar and its classifier `contract` ([A build fails over contracts](#a-build-fails-over-contracts)) |

## Commands time out

`wow.wait.timeout` (504) means the wait ran out, not that the command failed: **the command may have committed**. The problem names the last stage reached.

- Send it again **with the same request id**: a command that did commit is answered as a duplicate, with its original result.
- A wait for a later stage (`HANDLED`, a saga's hop, `SNAPSHOT`) waits for subscriptions too. A parked or lagging subscription on the chain makes it time out ([Waiting for Outcomes](../concepts/waits)).
- `wow.command.unknown` (503) means whether the command committed is unknown: a forward to the owner got no answer in time, the command topic did not acknowledge it, or a store did not say how its append ended. A retry with the same request id is safe.

## Commands are refused

| Code | Cause | Do |
| --- | --- | --- |
| `wow.command.busy` (429) | The node or the aggregate has too many commands in flight, or the partition's owner too many forwards from this node (`wow.command.forward.failures` with `wow.outcome=busy`) | Retry after `Retry-After`; add nodes if it persists ([Scaling and Capacity](./scaling#limits-a-node-enforces)) |
| `wow.aggregate.version-mismatch` (409, 412 with `If-Match`) | The aggregate is not at the version the client expected | Read it again and decide whether to retry |
| `wow.command.contended` (409) | Concurrent writers kept winning the version | Retry with the same request id |
| `wow.command.request-id-reused` (409) | The request id was used by another command | Give each command its own request id |
| `wow.aggregate.forbidden` (403) | A creating command names an aggregate of another owner or space than the caller's: one that exists, or one whose id is another owner's. Any other command on such an aggregate is answered `wow.aggregate.not-found` (404), as if it did not exist. Under an access policy: the caller may read the aggregate but not change it, or the command would create the aggregate, or change its access attributes, out of the caller's reach | Check the caller's scope and the id the command names ([What the kernel checks](../guide/ownership#what-the-kernel-checks)), or the policy's `write` and the caller's claims ([Where a policy applies](../guide/ownership#where-a-policy-applies)) |
| `wow.auth.forbidden` (403) | The authorizer refused the command for the caller | Check the caller's roles or policies |
| `wow.call.command-forbidden` (403) | A service called a command its entry under `wow.callers` does not list | List the command for that service, on the target |
| `wow.storage.unavailable` (503) | A store could not be reached | Check the database; retry with the same request id |
| `wow.node.standby` (503) | The node is on standby for a migration's cut-over | Activate it at the cut-over ([Deployment Model](./topology#standby-for-a-migrations-cut-over)) |

## A subscription parks aggregates

`wow.subscription.parked.aggregates` is above zero, and the ops console lists the aggregates. Each entry carries the error and the version the subscription stopped at.

- Fix the cause first: deploy the corrected handler, or bring back the outside system it calls.
- Then retry the aggregates: the subscription reads each one's stream again from where it stopped ([The Ops Service and Console](./ops-console)).
- A rejection of a command a saga derived parks the saga's source aggregate only when the command is of the saga's own context and the saga has no `@OnRejected` handler for it: a command to another context then asks for no receipt, and its target logs the rejection. A rejection of a command the saga called parks the aggregate when the saga has no handler for it, whatever the command's context ([Failures and Retries](../guide/failures)).

## A subscription starts over after a deploy

A saga or a reactor skips events, or a projection rebuilds from the beginning, right after a release, and the node logged at its start that a subscription group `has checkpoints of subscriptions it no longer has`. A processor or a handler was renamed, or moved to another context: its name is its identity, so it is a new subscription and starts as a new one does. Pin the old name with `@Name` and deploy again; the subscription then goes on from the old checkpoint ([Rename and rewind](./ops-console#rename-and-rewind)). What the new name handled in between stays handled.

## A subscription stops for every aggregate

`wow.subscription.paused.partitions` is above zero: the subscription took a failure for an outage of something every aggregate needs, a store or a downstream service, and pauses the partition until it is back. Nothing is parked meanwhile. Find what is down from the subscription's log; the partition resumes by itself.

## A subscription falls behind

`wow.subscription.backlog` grows and the subscriptions health component is degraded. The handler is slower than the log grows: a slow external call, writes to a store that needs an index, or a downstream that pushes back (`wow.subscription.throttled.partitions` above zero). Speed up the handler, then add nodes to spread its partitions ([Scaling and Capacity](./scaling#subscriptions)).

## A partition has no owner

The ownership health component is `DOWN`, or commands for some aggregates are slow while a node is gone. Leases of a node that died expire after `wow.cluster.lease-ttl` (10 s), then the partitions move. When they do not:

- The nodes cannot reach each other's internal port: check the network policy and `wow.node.address`, the address each node advertises.
- The lease store is down: the ownership health component's `leaseStore` detail says so.

## A topic of another context

| Symptom | Cause | Do |
| --- | --- | --- |
| A node stays not ready after it starts | A topic it reads is still joining its lease group | Wait; or set `wow.health.topics.required: false` |
| `wow.subscription.offset.gaps` is above zero | Retention deleted offsets a subscription had not read | Raise the topic's retention; decide on `wow.kafka.offset-gap` ([Kafka](./kafka#reading-other-contexts-streams)) |
| `wow.subscription.undecodable.messages` is above zero | Messages this version cannot decode | Upgrade the reading service's api, or the writer sends an unknown format |
| A topic's subscriptions stop, logging that a record is not a Wow envelope | Another producer writes the topic, such as 9.x on a shared broker | Give the services their own topics with `wow.kafka.topics.prefix` ([Kafka](./kafka#reading-other-contexts-streams)) |

## A build fails over contracts

A service's build, or a domain's, can stop over the api modules' contracts ([One contract](../concepts/contract#contracts-between-services)):

- **A task cycle through two contracts.** Two domain modules each use the other's generated code (a `<State>View`, `<Context>Contract`), so each needs the other's contract, which comes from compiling the other. Gradle reports it before anything runs:

  ```text
  Circular dependency between the following tasks:
  :a-api:assembleWowContract
  \--- :a-domain:generateWowContract
       +--- :a-domain:generateWowManifestFragment
       |    +--- :a-domain:compileJava
       |    |    +--- :a-domain:compileKotlin
       |    |    |    +--- :a-domain:kspKotlin
       |    |    |    |    \--- :b-api:wowContractJar
       |    |    |    |         +--- :b-api:assembleWowContract
       |    |    |    |         |    \--- :b-domain:generateWowContract
       |    |    |    |         |         +--- :a-api:wowContractJar
       |    |    |    |         |         |    +--- :a-api:assembleWowContract (*)
  ```

  The fix is in the code: one side reads the other's state without its view, by an event or a query. Using another context's types alone (its commands, events and values) never cycles.
- **"…which no peer contract on the class path lists as an event: it is in `<module>`, whose api module carries no contract listing it."** The event's api module was published without its contract: a types-only release (`-Pwow.contract.publish=false`, the first release of a cycle), or an api module that declares no `wowContract`. Depend on the module's full release.
- **"Unable to find a variant of '`<group>:<artifactId>:<version>`' with the requested capability: feature 'contract'", listing variants that provide `<group>:<project name>-contract`.** The api module was published by 10.0.0-beta.8's plugin under an artifactId other than its project's name, and that plugin offered the contract by the project's name alone. Publish the module again with a later plugin. Until then, ask for the name it offers, `capabilities { requireCapability("<group>:<project name>-contract") }`; a Maven build adds the classifier `contract`, which this does not touch.
- **"No Manifest fragment and no model provider: the module declares 0 @BoundedContext that KSP handed the processor…"** A domain module of aggregates or processors compiles with no bounded context the compiler can name. Either the module has none: declare one `@BoundedContext` in it, or set the KSP option `wow.context` (`wow { context = "…" }`). Or it declares one that KSP did not hand the processor, because KSP's incremental caches describe other sources, as after a source set's roots moved (the staged migration's switch back to `src/`): build the module clean, or rerun its KSP task (`--rerun`). With two or more `@BoundedContext` declarations, the message says so and asks for `wow.context` to name one.
- **"…in a build run with -Pwow.contract.publish=false…"** The property publishes api modules' types alone; a service needs their contracts. Build the service without it.
- **"…the contract and the types of its api module are of different versions."** A context's contract lists classes of which the class path holds only some. A consumer may hold a context's contract with none of its classes and address it by JSON; it may not hold it with only some of them. Depend on one version of the module, its jar and its classifier `contract`.

## Queries are refused or slow

`wow.query.busy` (429) means the store's query bound is reached; it adapts down while the store's appends are under pressure. `wow.query.timeout` (504) means the query ran longer than its budget: narrow it rather than run it again. Give the queries a pool or a replica of their own ([PostgreSQL](./postgresql#size-the-pools)).

`wow.query.invalid` (400) with the violation `replay-too-long` on a state read at a version or an instant, or a trace, means the state asked for is more commits away than `wow.query.state.max-replay` (10,000) allows: an older state is replayed from version 1. Read the aggregate's events through the events query instead, which has no such bound, or raise the bound if the deployment reads states that far back ([Read State](../guide/reading-state#replaying-is-bounded)).

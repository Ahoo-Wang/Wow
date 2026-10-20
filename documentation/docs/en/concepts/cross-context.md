---
title: Between Contexts
description: How bounded contexts exchange events, commands, results and reads in Wow, and which way to choose for each job.
---

# Between Contexts

A bounded context's log is private to it. Other contexts never read it and never write to it. They learn what happened from the streams it publishes, and they ask it to do something by sending it commands. This page explains each way contexts talk and when to use which.

## Choosing a way

| You want to | Use | The sender is done when |
| --- | --- | --- |
| React to what happened in another context | its **published stream** | — (you are the reader) |
| Ask another context to do something, and carry on | a **command** on its command topic, with receipts | the command is durably accepted |
| Ask another context to do something and get its answer now | a **synchronous call** to its internal port | the target has committed or rejected the command |
| Read another context's data | the **typed query client** | the query has answered |

Asynchronous messages between services go only through Kafka. Synchronous calls, reads and wait signals go to the other service's internal port, authenticated by service identity ([Identity, tenancy and access](./identity#the-internal-port)).

## Published streams

Each aggregate's commits are published, by default, as a stream on its own Kafka topic, `wow.<context>.<aggregate>.state`. A record on it is a **state event**: the commit's event stream plus the aggregate's state after it. A consumer gets the state with the change and does not have to ask the upstream service for it. An aggregate whose state is too large to send can publish its events without the state; one that should not be seen outside can stay unpublished.

Events are upgraded to their current revision before they are published, and the stream's schema is in the Manifest. The stream is the context's public language, and the Manifest compatibility check guards it ([One contract](./contract#compatibility)).

A subscription to another context's stream works exactly like a subscription to the service's own log: a handler, a checkpoint, at-least-once delivery, parking, signals. The handler cannot tell the source apart.

- Partitions of the topic are assigned by the same leases as the log's, not by a Kafka consumer group, and offsets are kept in the service's own checkpoint store. One consumer per node and topic feeds every subscription on that topic.
- Parking works per aggregate here too. A topic cannot be read per aggregate, so the ledger records the offset where the aggregate stopped, and resuming rescans that partition from it for the aggregate's records. An entry whose offset Kafka no longer retains cannot be resumed, only skipped.
- If the upstream publishes without state, the handler declares a nullable state. A service that expects state from a stream that has none does not start.

When the upstream context's API jar is on the classpath, the handler can take the upstream's own event classes.

## Commands to another context

A command for another context is written to that context's command topic, `wow.<context>.commands`, keyed and partitioned like its log. The sender, typically a saga, is done once Kafka has durably accepted the command. It does not wait for the target, and it is not in the target's cluster channel.

The target executes the command from its topic like any other: admission, deduplication by request id, decide, append. Because a saga's command has a request id derived from the commit that triggered it, a command sent twice commits once.

**A rejection comes back.** A rejection is a business outcome the sender needs to know about: the stock ran out, the payment was declined. The command carries where to reply, and the target writes a rejection receipt to the sending context's receipts topic, `wow.<context>.receipts`, before it acknowledges the command. The sending saga handles it in its rejection handler and can compensate. If the target crashes in between, the command is delivered again, rejected again, and the receipt sent again; the saga ignores the duplicate by command id. A saga can ask for a receipt on success too.

Only a technical failure while executing, not a rejection, goes to the target's failure ledger.

The same topic serves a context's own asynchronous commands when an aggregate type is routed through Kafka, so that a command is durable once accepted.

## Synchronous calls

Sometimes the sender needs the target's answer before it can go on: the number the target assigned, its version, its rejection. A saga then calls the target's command gateway synchronously, through the framework's client rather than hand-written HTTP:

- the call goes to the target service's internal port, authenticated as the calling service;
- it carries a request id derived from the root command, so a retried or replayed call does not commit twice;
- it carries the root command's scope, operator and request facts, which the target trusts only from a service identity;
- it can carry the wait, so a caller still waiting on the chain sees the target's stages;
- a rejection is returned as a rejection and is not retried; a call that gets no answer fails the handler, which is retried within its budget and then parks the aggregate.

A saga declares the commands it calls, and the Manifest lists them. The default between contexts remains the command topic with receipts. Use a call only where the target being up is a precondition anyway.

## Typed clients

Code that is not a saga, such as a service endpoint or a scheduled job, sends commands and runs queries through typed clients: a command client and a query API. For a context the service hosts, they run in-process, without encoding the command. For another context, a command goes to its command topic or over HTTP to its service, and a query goes over HTTP. The calling code is the same either way.

The remote side comes from the other context's contract, found in its API jar. A consumer depends only on that API jar; it writes no paths and no HTTP client code. Errors arrive as typed codes that say whether a retry is safe: a query is retried only when it was refused as busy (429), a timeout (504) is never retried as it is, and a streamed query resumes where it stopped.

## Contracts

All of this needs only the other context's API jar. It carries the context's contract, which the service reads from its classpath to check a command before sending it, to check a wait plan that reaches into the other context, and to check a published stream it subscribes to. [One contract](./contract#contracts-between-services) explains how the contract gets into the jar.

## Waiting across contexts

A wait can follow a chain into other contexts: an order committed, then the payment saga handled it, then the command it sent committed in inventory. Each hop signals the node the first command came from, directly. [Waiting for outcomes](./waits) explains the rules.

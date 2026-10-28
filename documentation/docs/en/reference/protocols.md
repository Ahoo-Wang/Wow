---
title: Wire Protocols
description: The structures Wow writes across process, storage and version boundaries — event streams, state events, Kafka envelopes, command results, wait signals and cluster messages — and the rules they evolve by.
outline: deep
---

# Wire Protocols

This page lists the structures Wow writes to storage, to Kafka and between nodes: what each field means and how the structures change between versions. Read it when you consume a published stream from a system that is not a Wow service, decode a command result yourself, or need to know what an upgrade may change.

The JSON examples are written by Wow itself: a test encodes each one and fails when the wire form changes.

## Principles

- **Facts, views and transport data are kept apart.** A fact, the event stream of a commit, is written once and never changed. A view, such as a state or a snapshot, is derived from facts and can be rebuilt. Transport data, such as an open wait or trace context, only helps delivery: domain code and queries never see it, and migration and replay drop it.
- **Names, not classes.** Commands, events and error codes are identified by the model's stable names. Classes can be renamed or moved without affecting history.
- **JSON on the wire.** Payloads are JSON, and their schemas are in the Manifest. Numbers keep their exact value and scale: `2.50` stays `2.50`.
- **Every structure has a format version.** See [Format versions](#format-versions).

## Names

| Name | Form | Example |
| --- | --- | --- |
| Aggregate type | `context.aggregate` | `bank.account` |
| Aggregate reference, as text | `context.aggregate/tenant/id` | `bank.account/t1/alice` |
| Command type | `context.aggregate.Command` | `bank.account.Deposit` |
| Event type | `context.aggregate.Event` | `bank.account.Deposited` |
| Error code | `wow.*` for the framework's; the aggregate's own are prefixed with its type | `wow.wait.timeout`, `bank.account.insufficient-balance` |
| Commit id | `<aggregate reference>@<version>` | `bank.account/t1/alice@2` |

A command type defaults to the command class's simple name, and an event type to the event class's; `@Name` overrides either.

## Common values

These values appear inside most structures.

| Value | JSON | Notes |
| --- | --- | --- |
| Aggregate reference | `{"type": {"context", "name"}, "tenantId", "id"}` | A creation that leaves its id to the server has the `id` `(none)`. |
| Scope | `{"ownerId", "spaceId"}` | Either may be null. On a command, the caller's scope; on a commit or a snapshot, the aggregate's after the commit. |
| Causation | `{"correlationId", "causationId"}` | `correlationId` is the root command's id; `causationId` is the direct cause, for a saga's command the commit it handled. A command sent directly by a caller has none and is its own root. |
| Operator | `{"id", "tenantId", "crossScope"}` | Whose name a commit is recorded under: the caller's subject and own tenant; `crossScope` when it acted in another tenant or scope. |
| Hop | `{"stage", "context", "subscription"}` | `stage` is `ACCEPTED`, `COMMITTED` or `HANDLED`; `subscription` only for `HANDLED`; `snapshot` names the snapshot subscription. |

Times are epoch milliseconds.

## Event stream

The event stream is the fact: the events one command produced, committed by one append. It is one entry of the log, and the storage backends lay its fields out as columns or document fields.

| Field | Meaning |
| --- | --- |
| `ref` | The aggregate. |
| `version` | The aggregate's version after this commit: 1 for its first, one more for each next. |
| `commandId`, `requestId` | The command that produced it and its idempotency key. |
| `commandType` | The type name of that command. A request id reused with another command type is refused; with the same type, it is a retry. |
| `commitTime` | When it was committed. |
| `events` | Each event's `name`, `revision` and `payload`, in order. Schedules set or cancelled are among them, as the system events `wow.ScheduleSet` and `wow.ScheduleCancelled`. |
| `scope` | The aggregate's owner and space after this commit. |
| `tags` | The aggregate's tags after this commit. |
| `causation` | The chain the command belongs to, if a saga sent it. |
| `headers` | The request facts admission recorded, such as `app_id`. |
| `operator` | Whose name the commit is recorded under; null for an anonymous caller. |

Beside the facts, a log entry has a **position**, its sequence number within its partition, and may have **transport data**: the wait context of an open wait, reservations to confirm, trace context.

Transport data has one form wherever it goes, the JSON object `{wait, reservations, trace, releases}` the envelope and the cluster channel embed; an absent wait, an empty list and an empty trace are left out. A log stores it as the bytes the node wrote and gives them back unchanged, without reading them, so a field a newer version adds needs no change to any backend.

## State event

A state event is a commit together with the aggregate's state after it. Handlers receive it in process, and published streams carry it on Kafka. Its fields are the event stream's, plus:

| Field | Meaning |
| --- | --- |
| `state` | `{version, schemaFingerprint, state}`: the state serialized once when the version committed, with the fingerprint of its schema. Null when the aggregate publishes without state. |
| `deleted` | Whether the aggregate is deleted after this commit. |

Events are upgraded to their current revision before they are published.

## Kafka envelope

Every message Wow writes to Kafka is an envelope.

| Part | Content |
| --- | --- |
| Key | The aggregate reference as text, so one aggregate's messages stay in one Kafka partition and in order. |
| Headers | `wow-format`, `wow-kind` and `wow-type`: the envelope's format, kind and type, readable without parsing the value. |
| Value | `{format, kind, id, type, payload, transport}`. |

| `kind` | `id` | `type` | `payload` | Topic |
| --- | --- | --- | --- | --- |
| `state-event` | the commit id, `ref@version` | the aggregate type | a [state event](#state-event) | `wow.<context>.<aggregate>.state`, unless the aggregate names another |
| `command` | the command id | the command type | a [command](#commands-on-a-topic) | `wow.<context>.commands` |
| `command-receipt` | the command id | the command type | a [receipt](#receipts) | `wow.<context>.receipts` of the sending context |

A consumer deduplicates by `id`. `transport` is null or holds the `wait` context and the `trace` propagation fields only.

A published state event:

<!-- sample: core/runtime/src/test/resources/protocols/state-event.json -->

```json
{
  "format": 1,
  "kind": "state-event",
  "id": "bank.account/t1/alice@2",
  "type": "bank.account",
  "payload": {
    "ref": {
      "type": {
        "context": "bank",
        "name": "account"
      },
      "tenantId": "t1",
      "id": "alice"
    },
    "version": 2,
    "commandId": "c-2",
    "requestId": "r-2",
    "commandType": "bank.account.Deposit",
    "commitTime": 1760950800000,
    "events": [
      {
        "name": "bank.account.Deposited",
        "revision": 1,
        "payload": {
          "amount": 100
        }
      }
    ],
    "state": {
      "version": 2,
      "schemaFingerprint": "43bfa906ccc015f9dc85b5ad142a24dcb99668d69f98ebd9d1ae73471c97be4d",
      "state": {
        "owner": "Alice",
        "balance": 100
      }
    },
    "scope": {
      "ownerId": "u-42",
      "spaceId": null
    },
    "deleted": false,
    "tags": {},
    "causation": null,
    "headers": {
      "app_id": "teller-app"
    },
    "operator": {
      "id": "u-42",
      "tenantId": "t1",
      "crossScope": false
    }
  },
  "transport": null
}
```

Event payloads and the state are embedded JSON objects, not escaped strings, so a consumer reads values by path.

## Commands on a topic

A command sent to a context's command topic, and a command forwarded between the nodes of a service, has the same wire form:

| Field | Meaning |
| --- | --- |
| `type` | The command type; the receiver decodes `body` by it, from its Manifest. |
| `id`, `requestId` | The command's id and idempotency key. A saga's command has a request id derived from the commit it handled. |
| `ref` | The aggregate it addresses. |
| `body` | The command's business data. |
| `expectedVersion` | The version the sender expects the aggregate to be at, or null. |
| `scope`, `operator`, `headers`, `causation` | As admission established them for the root command; a saga's command inherits them. |
| `replyTo` | Only on a command a saga sent to another context: `{context, subscription, ref, version, committed}`, the saga's commit to send the receipt for; `committed` asks for a receipt on success too. |

The topic's partition is the one the target context's log assigns to `ref`, so the topic has as many partitions as that log. The wait plan does not travel with the command; the wait context of an open wait goes in `transport`:

<!-- sample: core/runtime/src/test/resources/protocols/command.json -->

```json
{
  "format": 1,
  "kind": "command",
  "id": "c-3",
  "type": "bank.account.Withdraw",
  "payload": {
    "type": "bank.account.Withdraw",
    "id": "c-3",
    "requestId": "tr-9@1#0",
    "ref": {
      "type": {
        "context": "bank",
        "name": "account"
      },
      "tenantId": "t1",
      "id": "alice"
    },
    "body": {
      "amount": 30
    },
    "expectedVersion": null,
    "scope": {
      "ownerId": null,
      "spaceId": null
    },
    "headers": {},
    "causation": {
      "correlationId": "c-1",
      "causationId": "bank.transfer/t1/tr-9@1"
    },
    "operator": {
      "id": "u-42",
      "tenantId": "t1",
      "crossScope": false
    },
    "replyTo": {
      "context": "bank",
      "subscription": "TransferSaga.onRequested",
      "ref": {
        "type": {
          "context": "bank",
          "name": "transfer"
        },
        "tenantId": "t1",
        "id": "tr-9"
      },
      "version": 1,
      "committed": false
    }
  },
  "transport": {
    "wait": {
      "waitId": "w-7",
      "origin": {
        "node": "bank-1",
        "address": "10.0.0.5:9090",
        "service": "bank-service"
      },
      "remaining": [
        {
          "stage": "handled",
          "context": "bank",
          "subscription": "snapshot"
        }
      ],
      "deadline": 1760950810000
    }
  }
}
```

The wait context names the node the command came from (`origin`, with its internal address), the hops still to reach and the absolute deadline.

## Receipts

The target of a cross-context command writes a receipt to the sending context's receipts topic before it acknowledges the command. A receipt is keyed by the reference of the commit the saga handled, so the receipts of one saga aggregate stay in order, and its `id` is the command's.

| Receipt | Payload |
| --- | --- |
| Rejection | `{commandId, commandType, ref, error, causation, executionId, operator, scope, headers}`, without the command's body |
| Success, when asked | `{commandId, commandType, ref, version, result, causation, executionId, operator, scope, headers}`, where `result` is the [command result](#command-result) at its `COMMITTED` hop, without the state |

`executionId` is `{subscription, ref, version}`: the saga's handler call the command came from. `headers` holds only the request facts that propagate.

<!-- sample: core/runtime/src/test/resources/protocols/command-receipt.json -->

```json
{
  "format": 1,
  "kind": "command-receipt",
  "id": "c-3",
  "type": "bank.account.Withdraw",
  "payload": {
    "commandId": "c-3",
    "commandType": "bank.account.Withdraw",
    "ref": {
      "type": {
        "context": "bank",
        "name": "account"
      },
      "tenantId": "t1",
      "id": "alice"
    },
    "error": {
      "code": "bank.account.insufficient-balance",
      "message": "The balance is 20.",
      "violations": [],
      "extensions": {}
    },
    "causation": {
      "correlationId": "c-1",
      "causationId": "bank.transfer/t1/tr-9@1"
    },
    "executionId": {
      "subscription": "TransferSaga.onRequested",
      "ref": {
        "type": {
          "context": "bank",
          "name": "transfer"
        },
        "tenantId": "t1",
        "id": "tr-9"
      },
      "version": 1
    },
    "operator": {
      "id": "u-42",
      "tenantId": "t1",
      "crossScope": false
    },
    "scope": {
      "ownerId": "u-42",
      "spaceId": null
    },
    "headers": {}
  },
  "transport": null
}
```

## Command result

One structure reports the outcome of a command everywhere outside the kernel: the HTTP response, each event of a server-sent event stream, wait signals, receipts and the clients.

| Field | Meaning |
| --- | --- |
| `format` | The structure's format version. |
| `commandId`, `requestId`, `correlationId` | The command, its idempotency key and the root of its chain. |
| `ref` | The aggregate. |
| `hop` | The hop of the wait plan this result reports. |
| `function` | Who produced it: `{kind, context, processor, name}`. `kind` is `command`, `project`, `react`, `saga`, `snapshot`, `reservation` or `timers`; for a command, `processor` and `name` are the aggregate and the command; for a subscription, its processor and function. |
| `status` | `succeeded`, `rejected` or `failed`. |
| `version` | The aggregate's version after the commit, or unchanged when nothing was appended. |
| `duplicate` | The request had already committed; it was not decided again. |
| `error` | For `rejected` and `failed`: `{code, message, violations, extensions}`, each violation `{path, code, message}`. |
| `results` | Extra results decide returned; present only on the first commit, never on a duplicate. |
| `derived` | For a saga hop: the ids of the commands it sent for this commit. |
| `signalTime` | When the result was produced. |
| `transformedFrom` | When a transformer rewrote the command at admission: `{command, aggregateId}` of the original. |
| `chainEnded`, `pending` | The wait ended before its last hop because a saga sent nothing further: `chainEnded` is true and `pending` lists the hops not reached, in order. |

How the kernel's outcomes map to a result at `committed`:

| Outcome | `status` | Notes |
| --- | --- | --- |
| Committed | `succeeded` | `version` is the new version; `results` as decide returned them |
| Already committed | `succeeded` | `duplicate` is true; no `results` |
| No change | `succeeded` | `version` is unchanged |
| Rejected | `rejected` | `error.code` is the rejection's code |
| Contended | `failed` | `wow.command.contended`; retry with the same request id |
| Unknown | `failed` | `wow.command.unknown`; retry with the same request id, which cannot commit twice |
| Wait timed out | `failed` | `wow.wait.timeout`; the waiter stopped waiting, which does not mean the command failed |

Over HTTP, a successful command answers 200 with the result. A rejection or a failure answers `application/problem+json` (RFC 9457) with `type` set to `urn:wow:error:<code>`, the extension members `code` and `violations`, and the whole result under `result`, so a client parses one shape either way. With `Accept: text/event-stream`, each hop's result is one event, and the stream closes after the last.

## Wait signal

A hop that completes sends a signal straight to the internal port of the node the command came from. The signal carries the result for that hop:

<!-- sample: core/runtime/src/test/resources/protocols/wait-signal.json -->

```json
{
  "format": 1,
  "waitId": "w-7",
  "result": {
    "format": 1,
    "commandId": "c-2",
    "requestId": "r-2",
    "correlationId": "c-2",
    "ref": {
      "type": {
        "context": "bank",
        "name": "account"
      },
      "tenantId": "t1",
      "id": "alice"
    },
    "hop": {
      "stage": "handled",
      "context": "bank",
      "subscription": "snapshot"
    },
    "function": {
      "kind": "snapshot",
      "context": "bank",
      "processor": "snapshot",
      "name": "snapshot"
    },
    "status": "succeeded",
    "version": 2,
    "duplicate": false,
    "error": null,
    "results": {},
    "derived": [],
    "signalTime": 1760950800042,
    "transformedFrom": null,
    "chainEnded": false,
    "pending": []
  }
}
```

A node may send several signals to one node as a JSON array of them. The receiving node deduplicates a signal by its wait id, its hop and the context and subscription that sent it.

## Cluster channel

The nodes of one service forward commands to each other on their internal ports, naming their service in the `Wow-Service` header. A node takes a forward only from a node of its own service: without the header it answers 401 `wow.auth.unauthenticated`, from another service 403 `wow.auth.forbidden`.

| Message | Fields |
| --- | --- |
| Forward | `{v, command, transport, deadline}`: the [command](#commands-on-a-topic) in its wire form, the transport data (the wait context of an open wait and the trace propagation fields, null when there is neither) and the absolute deadline. |
| Reply | `{v, result, reached, refused}`: either the owner's `result` at `COMMITTED` with the later hops it `reached` at once, or `refused` with one of `not-owner`, `expired`, `unknown-type`, `too-large`. |

A refused forward is executed by the sending node itself. A forward that was sent and then timed out is not: the sender answers that the outcome is unknown, and the caller retries with the same request id.

## Snapshot

A snapshot is the latest state of one aggregate with its metadata, as the snapshot stores keep it:

| Field | Meaning |
| --- | --- |
| `ref`, `version` | The aggregate and the version of the last commit folded into the state. |
| `state` | The serialized state. |
| `schemaFingerprint` | The fingerprint of the state's schema, the same as a state view's. |
| `stateRevision` | The aggregate's state revision when the snapshot was written. A snapshot of a lower revision never replaces one of a higher revision. |
| `scope`, `tags`, `deleted` | The aggregate's owner and space, tags and deletion mark, as of the last commit. |
| `firstEventTime`, `lastEventTime` | The commit times of its first and last commits. |
| `operator`, `firstOperator` | The operators of its last and first commits. |
| `schedules` | The schedules it keeps, each rule by its key. |

These are also the system fields of the `snapshot` query model.

## Format versions

The structures with a format version are: the log entry's facts and transport data, the snapshot record, the state event, the command's wire form, the command result, the wait signal, the Kafka envelope and the cluster channel's messages.

- Logs and snapshot stores record their format version per table. Every message on the wire carries its own: `format`, or `v` on the cluster channel.
- **Adding is not a format change.** A new optional field with a default keeps the format version. Readers ignore fields they do not know, and fill in the default for a field that is missing.
- **Within a major version**, a new field is first read by one minor version and written only by the next, so two adjacent minor versions can run side by side during a rolling upgrade.
- **A change old readers cannot read** waits for the next major version, whose migration tool converts the data.

A reader refuses an envelope whose `format` it does not know, and an envelope `kind` it does not know.

---
title: HTTP API
description: Every route of a service's public port, its request headers, the Wow-Wait grammar, responses, server-sent events and problems.
outline: deep
---

# HTTP API

A service's public port serves these routes, materialized from its Manifest. [Serve and Call the HTTP API](../guide/http) explains how to use them; [Wire Protocols](./protocols) describes the bodies.

## Routes

**Commands**

| Method and path | Sends |
| --- | --- |
| `POST /<context>/<aggregate>/{id}/<command>` | The command, in kebab case, to the aggregate `{id}` |
| `POST /<context>/<aggregate>/<command>` | A creating command to a new aggregate whose id the server gives it; a command whose body names its aggregate, to that one; a mounted or `@Unaddressed` command, to the aggregate its transformer finds; any command of an aggregate whose id is its owner's (`idSource = OWNER`), to the caller's own |
| `DELETE /<context>/<aggregate>/{id}` | `DeleteAggregate` |
| `DELETE /<context>/<aggregate>` | `DeleteAggregate` of the caller's own aggregate, for an aggregate whose id is its owner's |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.restore-aggregate` | `RestoreAggregate` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-owner` | `TransferOwner`, body `{"ownerId": "…"}` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-space` | `TransferSpace`, body `{"spaceId": "…"}` |
| `POST /<context>/<aggregate>/wow.snapshot.rebuild-snapshot` | A rebuild of the snapshots of `{"ids": [...]}` |
| `POST /<context>/wow.schedule/{job}/<command>` | A job command: `enable-job`, `reschedule-job`, `disable-job` |

An internal command (`@InternalCommand`, `"internal": true` in the Manifest) has its routes on the internal port alone, where the services' calls reach it: on this port a request to one is the `404` problem of any unknown path, and `GET /wow/openapi` leaves the command out ([Keep a command internal](../guide/aggregates#keep-a-command-internal)). Only the lifecycle commands an aggregate lists in `@AggregateRoot(lifecycle)` get routes. The body of a command is its JSON; an empty one is `{}`. A command whose body names its aggregate (`@AggregateId`) has the route without `{id}` alone, and the Manifest lists its `idProperty`, the property of the body that holds the id: a blank one is `wow.command.malformed` (400), and so is a request that names another aggregate beside the body. The snapshot rebuild and the job commands are system commands of Wow's built-in aggregates: a service without a command authorizer refuses them on this port (403 `wow.auth.forbidden`), never allowing them by default ([Authorize commands](../operate/security#authorize-commands)).

**Reads**

| Method and path | Answers |
| --- | --- |
| `GET /<context>/<aggregate>/{id}/state` | The latest state; `?version=n`, `?at=<instant>` or `?minVersion=n` |
| `GET /<context>/<aggregate>/{id}/state/trace` | The commits, `?from=`, `?to=`, `?limit=` (100, at most 1,000) |
| `GET /<context>/<aggregate>/state`, `…/state/trace` | The same, of the caller's own aggregate, for an aggregate whose id is its owner's |
| `POST /<context>/<aggregate>/snapshot/query` | A snapshot query |
| `POST /<context>/<aggregate>/events/query` | An events query |
| `GET` on either query path | The model's capability descriptor, with its version as `ETag` |
| `POST /wow/dataset/<context>/<dataset>/query`, its `GET` | A dataset query, its descriptor |
| `GET /wow/dataset` | The datasets the caller sees |

**The service**

| Method and path | Answers |
| --- | --- |
| `GET /wow/manifest` | The service's merged Manifest, its internal commands among them, marked `internal`; on the public port only with `wow.http.manifest.enabled: true` (off by default), always on the internal port to the cluster's services |
| `GET /wow/openapi` | The OpenAPI 3.1 document of these routes (`wow.http.openapi.enabled`): the internal commands are not among them, and each aggregate's records are typed by its Manifest, the `state` by its state schema and the `access` by the access attributes it declares |

A context's own service routes, the handlers its code declares, are served as its Manifest lists them. `wow.http.ops.enabled: true` (off by default) also serves the operator's routes: the parked entries under `/wow/ops/parked` and the read of the node's subscriptions, `GET /wow/ops/subscriptions` ([Ops Console](../operate/ops-console)).

Any other request under a context's path is a `404` problem.

## Request headers

| Header | On | Means |
| --- | --- | --- |
| `Wow-Request-Id` | Commands | The caller's id of the request: a retry with the same id is answered as done (`duplicate`), never decided twice; the same id with another command is `wow.command.request-id-reused` |
| `Wow-Wait` | Commands | How far to wait before answering (below); `snapshot` by default, `committed` for an aggregate without snapshots, where an explicit `snapshot` is `wow.wait.invalid` |
| `Wow-Wait-Timeout` | Commands | How long to wait: `500ms`, `10s`, `1m`, the unit right after the number, or ISO-8601, one millisecond or more (`10 s` and `PT0.0001S` are `wow.wait.invalid`); `wow.wait.default-timeout` (10 s) by default. A longer one is capped at `wow.wait.max-timeout` (1 min): the timeout is the client's bound on its wait, and the node's maximum bounds it |
| `If-Match` | Commands | The version the aggregate must be at, as a strong tag: `"3"`, or the `"3-…"` a state read wrote, whose digest is not compared; `412 wow.aggregate.version-mismatch` when the aggregate is at another. Tags that name no version (`W/"3"`, `"03"`, `"2147483648"`) or two are `400 wow.request.malformed`. Its lines are one list ([Entity tags](./protocols#entity-tags)) |
| `If-None-Match` | State reads, descriptors | The last `ETag`; `304` when it still holds. One that does not parse is ignored: a cache condition fails open ([Entity tags](./protocols#entity-tags)) |
| `Accept: text/event-stream` | Commands, `list` queries | Server-sent events (below) |
| `Last-Event-ID` | Streamed `list` queries | The position to resume after |
| `Wow-Scope` | Every route | For a caller allowed to act across scopes: the tenant, owner and space to act in, an RFC 8941 dictionary, `tenant="t1", owner="u1", space="s1"`. It always names a scope: an owner or a space it leaves out is none, so `tenant="t1"` alone acts in `t1` with no owner or space checked; Wow's clients write the caller's own scope for a request that names only a tenant, which keeps it. On a query route, `tenant=*` or `tenant=("t1" "t2")` reads across tenants when `wow.query.allow-cross-tenant` is on. Its lines are one dictionary, joined by commas; a member given twice is malformed (`wow.command.malformed`, `wow.query.malformed` on a read) |
| `Wow-Space` | Every route | The space, among the caller's own, to act in |

The headers `wow.http.request-facts` lists are copied into the command and written into its event stream.

The prefix `Wow-` is Wow's. Besides the headers above, the public port takes `Wow-Service`, which it does not read, and the development identity headers (`Wow-User`, `Wow-Owner`, `Wow-Roles`, `Wow-Tenant`, `Wow-App`), which it reads in a development profile alone. Nodes are upgraded before the clients: a client that sends a `Wow-` header of a newer Wow is refused by an older node. The internal port keeps reading what a peer of the next minor version sends.

### What the public port refuses {#refused}

Nothing a client sends outside the body is read as something else, or dropped:

| Request | Answer |
| --- | --- |
| A `Wow-` header the port does not take (`Wow-Wiat`, a typo of `Wow-Wait`) | `400 wow.request.malformed`, naming it |
| A Wow header given in two lines or more (`Wow-Request-Id: r1` and `Wow-Request-Id: r2`), but `Wow-Scope` | `400 wow.request.malformed`, naming it: a proxy that appends a line does not choose the value |
| `Wow-Call`, the header of a service's synchronous call, on any route: a command, a query, a state read, a service route | `403 wow.auth.forbidden`, whatever the credential: calls are taken on the internal port alone |
| Matrix parameters in the path (`/shop/cart/c-1;x=1/add-item`), a `;` that names none included (`c-1;`, `c-1;=v`) | `400 wow.request.malformed`: no route takes them |
| A body of another type than `application/json` or `application/<name>+json`, any parameters aside (`*/*`, `application/*`, `image/x+json`) | `415` |
| An aggregate id the [id rule](#aggregate-ids) refuses: a control character (`a%00b`), white space at either end (`%20c-1`), `.` or `..`, a `/`, `;` or `\` (`a%2Fb`), longer than 256 characters, or longer than 512 bytes in UTF-8 | `400 wow.command.malformed`. A unicode id (`%E8%B4%AD`) is taken as it is: a client encodes the ids it sends. An id long enough that the request line passes its limit is refused first, `414` ([before any route](#server-refusals)) |
| A blank id on a state read (`/shop/cart/%20/state`) | `400 wow.query.malformed` |
| A parameter of a state read or a trace it does not take (`?verison=1`), or one given twice (`?version=1&version=9`) | `400 wow.query.invalid`, the violation naming it |
| `version`, `minVersion`, `from`, `to` or `limit` that is not ASCII digits without a sign or a leading zero (`%2B1`, `001`, `١`) | `400 wow.query.invalid`, the violation naming it |
| A number parameter of a service route that is not a number as JSON writes one (`NaN`, `Infinity`, a space, `+1`, `01`; for a whole number, a fraction or an exponent) | `400 wow.route.malformed`, the violation naming it |

### What the server refuses before any route {#server-refusals}

Four of the server's limits are met before a route reads the request. Each answer is a problem of its status alone, with no `code` ([Problems](#problems)); its `detail` names the limit and the key that sets it.

| Request | Answer | Limit: key, default |
| --- | --- | --- |
| A request line (the method, the path with its query, the version) longer than the limit | `414`, and the connection closes | `server.netty.max-initial-line-length`, 4 KB |
| A header block larger than the limit | `431`, and the connection closes | `server.max-http-request-header-size`, 8 KB; 16 KB in CoSec's [gateway mode](../operate/security#run-services-in-gateway-mode) |
| A request the server cannot read as HTTP, such as two `Content-Length` headers that disagree | `400`, and the connection closes | |
| A body larger than the codecs take in memory | `413` | `spring.http.codecs.max-in-memory-size`, 256 KB |

The internal port answers the first three alike, at Reactor Netty's own limits, 4,096 and 8,192 bytes, which no key sets.

A body past the codecs' limit is refused from its `Content-Length`, before any of it is read: a client that sent `Expect: 100-continue` gets the `413` in place of `100 Continue`, and sends no body. A body sent without a length is refused once its reading passes the limit. When the connection closes after an answer given before the request's body was read to its end (`Connection: close`, HTTP/1.0, or a refusal above), the server shuts its side down and reads what the client still sends, for at most 5 seconds, before closing: the answer reaches the client rather than a reset.

### Aggregate ids {#aggregate-ids}

One rule decides which aggregate ids exist, on every path a command takes: the public and internal ports, the in-process `CommandClient`, a peer's call, a context's command topic, a saga's and a timer's commands, an id a generator gives, and the test DSL. It refuses an id:

| The id | Why |
| --- | --- |
| Longer than 256 characters, counted in UTF-16 code units (an emoji counts two) | A gateway's path check refuses a longer path segment: CoSec 8 counts a segment decoded, at most 256. Not every store's index keys take a longer one either |
| Holding a control character (`\u0000` to `\u001f`, `\u007f` to `\u009f`) | Not every store keeps it |
| With white space at either end | A reader would not see it: another aggregate than the one without it |
| `.` or `..` | A proxy normalizing the path resolves it away |
| Holding `/`, `;` or `\` | A gateway's path check refuses it encoded in a path: CoSec 8 answers `%2F`, `%3B` and `%5C` with 400, before the service sees the request |
| Longer than 512 bytes in UTF-8 | The same check refuses a path longer than 2,048 characters as received, percent-encoded. A route by id leaves its id 1,536 of them, and a client writes at most three characters for each byte (`%E8`), so an id of 512 bytes fits however it is encoded. That is 256 characters of most scripts (`x`, `é`), 170 of Chinese, Japanese or Korean (`购`; 171 do not fit), and 128 emoji |

So every aggregate is reachable by each route that names its id, behind such a gateway too. The rest of a route by id, its context, its aggregate, its command and its separators, takes at most the other 512 characters, percent-encoded, and each of its segments at most 256 characters, as the gateway counts one: the build refuses a Manifest with a longer one, naming the route and the name to shorten. The longest route by id of an aggregate is usually a lifecycle command's (`…/{id}/wow.lifecycle.transfer-space`) or the trace of its state (`…/{id}/state/trace`).

The gateway counts a segment decoded from CoSec 8.0.0-beta.6; an earlier one refuses a segment longer than 256 characters as received, so an id of more than 28 characters such as `购` is not reachable behind it ([Longer aggregate ids](../operate/upgrades#longer-aggregate-ids)). A gateway whose route patterns read a path as received, without decoding it, matches a segment of at most 256 characters as written, encoded. Everything else is an id: a dot or a space inside it, `%`, `?`, `#`, `+`, and any unicode character, which a client encodes in the path.

| Where the id comes from | Answer |
| --- | --- |
| A request on a port, the in-process `CommandClient`, a peer's call: the id in the path or the body, or the caller's owner for an aggregate whose id is its owner's | `400 wow.command.malformed`, naming the rule the id broke |
| A command a context's topic delivers | Rejected as `wow.command.malformed`, with the rejection receipt when one is asked; the aggregate is not parked |
| A saga's derived command, a timer's command | Rejected as `wow.command.malformed`: the saga's `@OnRejected` handler takes it; a timer's rejection is recorded as for any other |
| An aggregate's id generator | The command fails: a generator that gives such an id is the service's defect |
| The test DSL | The specification fails, as the service would refuse its command |

An aggregate stored with such an id before this rule held is not changed: a command naming it is refused as malformed, without a gateway in front too, and a query still returns it. A 9.x store is checked before the cut-over: `migrate-data check` lists each instance whose id the rule refuses ([Migrate the Data](../migrate/data#_2-check-the-map)).

## Wow-Wait

```text
plan := hop (">" hop)*
hop  := "accepted" ["(" context ")"]
      | "committed" ["(" context ")"]
      | "snapshot" ["(" context ")"]
      | "queryable" ["(" context ")"]
      | "handled(" [context "/"] subscription ")"
```

| Hop | Reached when |
| --- | --- |
| `accepted` | The command passed admission; durable only for a command routed through Kafka |
| `committed` | Its events are appended, or it was settled without appending |
| `snapshot` | The snapshot holds the commit |
| `queryable` | The store the aggregate's snapshot queries read holds the commit: its copy where its queries read another store (`query.snapshot.store`), else the same as `snapshot` |
| `handled(sub)` | The subscription `sub`, `processor.function`, handled the commit |

A hop without a context is in the command's context. After a saga's `handled` hop, the following hops apply to the commands the saga derived, in their contexts. A plan has at most `wow.wait.max-hops` (16) hops. A `Wow-Wait` longer than 4,096 characters, or of more than 32 hops, is `wow.wait.invalid` before any hop is read. A hop of a context the node has no Manifest of is checked where the chain arrives, unless `wow.wait.unknown-contexts: refuse` refuses it with `wow.wait.invalid`.

## Responses

| Status | When |
| --- | --- |
| `200` | The command's result at the end of its wait; a state; a query's answer |
| `202` | A command whose wait ends at `accepted` |
| `304` | A state or a descriptor whose `ETag` the request named |
| `4xx`, `5xx` | An RFC 9457 problem (below), with `Retry-After` when a retry may succeed later |

**Server-sent events.** A command with `Accept: text/event-stream` answers `200` once accepted, then one event per result as each hop its plan names is reached (a hop that several derived commands reach gives one each), its `id` the result's index, its name the hop's stage, its data the result, until the wait ends. A refusal before it is accepted is a problem with its own status instead: an admission refusal, a full node (`429 wow.command.busy`), an invalid plan, a command topic that did not take it. After the head, a rejection, a timeout or a failure is the stream's last result, a failure a FAILED result with its code (`wow.storage.unavailable`, `wow.command.unknown`), and so is a chain that ended before its plan did (`chainEnded`). After the last result, one more event, `end` with `{"reason":"complete"}` and no `id`, says the stream is whole; it is no result. A stream that ends without it, however cleanly (a node that stopped, a connection cut), leaves the command's outcome unknown: send it again with the same `Wow-Request-Id`. A reader does not infer the end from the plan. A comment, `: keep-alive`, is written at once and every 15 seconds while the stream runs, so a gateway's idle timeout does not close the stream; a reader of server-sent events skips it. A streamed `list` answers one `row` event per record, its `id` the cursor of the row's position (none when the list's sort cannot be resumed), then an `end` event `{"reason", "resumable"}`: `complete` (`"resumable":false`) once every record was sent, `hold-expired` after `wow.query.stream.max-hold`, `node-stopping` when the node stops, or `permit-reclaimed` when the store had no query permit for the next chunk in time. In a resumable stream every reason but `complete` resumes with `Last-Event-ID`. A failure after the first row ends the stream with an `error` event holding the problem.

## Problems

Every error is `application/problem+json`:

| Member | Holds |
| --- | --- |
| `type` | `urn:wow:error:<code>`; `about:blank` for a problem that is only HTTP's, such as a missing route |
| `code` | The error code, a framework code (`wow.*`) or a domain code; absent on an `about:blank` problem |
| `status` | The HTTP status |
| `title` | A short summary of the code for people, the same for every occurrence: a framework code's title ([Error Codes](./error-codes)), a domain code's `@ErrorCode(description)` |
| `detail` | What happened to this request, for people: the rejection's or failure's message |
| `violations` | The field-level violations: `path`, `code`, `message`. A command constraint's `code` is the constraint's name in lower-kebab: `NotBlank` is `not-blank`. A body that does not read as its command (`wow.command.malformed`) gives one violation, the property's path and `wrong-kind`, `out-of-range`, `invalid-format`, `not-in-enumeration`, `duplicate-property` or `unknown-property` ([JSON Values](./json-values)). |
| `retry` | What a retry may do, on every problem: `none`, `same-request` (send again with the same `Wow-Request-Id`) or `new-request`; with `Retry-After` when it is not `none`, except on `wow.auth.unavailable`, whose headers are CoSec's (below) |
| `result` | A command's whole result, when the command produced one |
| `version` | The aggregate's version the problem answered: a rejection's, or the current one of a state read below its `minVersion` |
| Any other member | A rejection's or a service route refusal's structured extensions, by name. One named like a member above, or `instance`, is left out, even on a problem without that member. So is one named `requestId`, `traceId` or `errors`, names Wow keeps for members of its own: CoSec's refusals carry `requestId` and `traceId` ([CoSec's refusals](../operate/security#cosec-refusals)). The first time a service leaves a name out, it logs a warning naming it and the error code, never the value; a rejection's result still carries it |

A domain code's status is its `@ErrorCode(status)`, 422 by default, overridable per code with `wow.http.errors.[<code>].status`. [Error Codes](./error-codes) lists the framework's codes. A code under `wow.` there that the framework does not answer refuses the start, naming the nearest framework codes; a code of the application's that neither the service's Manifest nor a peer contract on the class path declares starts with a warning, since it may still be right (a rejection's code no `@ErrorCode` declares, a `ProblemMapper`'s, a peer's). The application's own routes answer their failures as problems too (`wow.http.global-errors.enabled`, on by default).

A request CoSec refuses is answered before any route, with CoSec's status, headers and problem (`https://cosec.ahoo.me/problems/<type>`, CoSec's `code`). With `wow.cosec.problems: wow` its body is Wow's problem: `wow.request.malformed`, `wow.auth.unauthenticated`, `wow.auth.forbidden`, `wow.request.rate-limited` or `wow.auth.unavailable`, and `about:blank` for CoSec's internal error, with CoSec's `type` and `code` in the member `cosec` ([CoSec's refusals](../operate/security#cosec-refusals)). Its headers stay CoSec's, so `wow.auth.unavailable` (503) has `retry: new-request` and no `Retry-After`: CoSec sets none on it. The catalog's wait for the code, one second, is what Wow's clients wait without the header ([Error Codes](./error-codes)), not a value a response carries. Only these refusals carry `wow.request.rate-limited` and `wow.auth.unavailable`.

### The application's own routes {#application-routes}

What Spring WebFlux raises on a route the application serves beside Wow's, annotated or functional, is answered so:

| The request | Answer |
| --- | --- |
| Matches no route | 404 `about:blank`, its detail the method and path: `No route GET /orders.` |
| Uses a method the route does not serve | 405 `about:blank`, with `Allow` |
| Sends a body of a media type the route does not take | 415 `about:blank`, with `Accept` |
| Accepts none of the types the route answers in | 406 `about:blank` |
| Cannot be read: a body the codecs cannot read, a parameter, header or path variable missing or of the wrong type | 400 `wow.request.malformed`, Spring's reason as its detail, and one violation naming what does not read: the parameter by its name, or `body`, with the code `wow.request.malformed` |
| Fails a `@Valid` body's or a method's constraints | 400 `wow.request.invalid`, one violation per constraint it broke, in a command's shape: the path (a body's field, `items[0].quantity`; a parameter's name, with its element's index or key, `ids[1]`; empty for the body itself or across parameters), the constraint's name on the wire (`not-blank`; `invalid` when the error names none), and the message |
| Meets a `ConstraintViolationException` of a service it calls | A 500 that says nothing: a service method's constraints are its own contract, not the request's. A `ProblemMapper` may answer it |
| Meets a `ResponseStatusException` the application throws | Its status alone, `about:blank`, its reason as the detail of a 4xx |

A status that has nothing to add to itself is answered as `about:blank`, RFC 9457's problem of the status alone, on Wow's routes too. With `wow.http.global-errors.enabled` off, the application's routes answer as Spring does.

### The application's own failures {#mapped-failures}

A failure the framework does not know, such as a failed call to another system, is answered as a 500 that says nothing of its cause, which is logged. The application answers its own failures with its own codes through `ProblemMapper` beans (`me.ahoo.wow.api.problem`): given the failure, a mapper returns the `WowProblemException` to answer with, or null when the failure is not its own. A shared library contributes one the same way, through its own auto-configuration.

```kotlin
@Bean
@Order(0)
fun catalogProblems(): ProblemMapper = ProblemMapper { failure ->
    (failure as? WebClientResponseException)?.let {
        WowProblemException("shop.catalog.unreachable", 502, RetryAdvice.SAME_REQUEST, null, "The catalog did not answer.")
    }
}
```

- **Where.** On the application's own routes (`wow.http.global-errors.enabled`), a service route's handler, the state reads, the command routes and the queries, a streamed list's `error` event included. Not on the internal port or the operator's routes, and never for a command's failure: a decide function that throws is the command's result, `wow.command.failed` ([Rejections and Error Codes](../guide/rejections)). Nor for a saga's synchronous call or the command client, which receive the other service's answer as that service mapped it.
- **What a mapper sees.** Only what would otherwise be answered as a 500 that says nothing (a query's `wow.query.failed`): never a `WowProblemException`, a command's rejection, an authentication or admission refusal, a store that is unavailable, or a body larger than the codecs take, nor one of these as the cause of another failure. The application's own exception that wraps one may still be mapped.
- **Order.** The mappers are asked in their bean order (`@Order`), for the failure and then each of its causes; the first that gives a problem answers. A mapper that throws is passed over, and its failure logged.
- **Checks.** A mapped problem whose code the framework reserves (`wow.*`), whose status is not 4xx or 5xx or is 401 (authentication's alone), or which carries a command's result is not taken: the failure is answered as a 500 that says nothing, and the mapper and code are logged once. `wow.http.errors.[<code>].status` still decides a mapped code's status.
- **Catalog.** Declare a mapped code in a context with `@ErrorCode`, or in a service route's `errors`, so that clients and the Manifest know it. A code that none lists is answered all the same, and warned of once.
- **Logs.** A mapped 5xx is logged as a warning with its failure, a mapped 4xx at debug.

The problem's message is its `detail`, which the caller reads: say nothing in it that the caller should not see. A mapper may list the fields the request broke as the problem's `violations` (`WowProblemException(…, violations = listOf(Violation.of(path, code, message)))`), in the shape of a command's.

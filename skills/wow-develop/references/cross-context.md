# Other contexts

The job: a context needs another context's facts, or needs it to do something. Contexts share no database and no classes beyond the other's published api module; they meet on Kafka (or a synchronous call where the answer is needed at once). Verify each key and annotation in the target's version.

## Reading another context's events

- A typed handler `StateTypedEvent<View, E>` of the upstream api's event class: the service's Manifest finds the aggregate from the upstream contract. `@OnEvent(aggregates = [<Context>Contract.<Aggregate>.QUALIFIED_NAME])` names it for a handler of the whole stream (`StateEvent<View>`) or an event two aggregates share.
- Events arrive as the upstream api's classes when their revision is the one its contract (in the api jar, `META-INF/wow/contract.json`) declares; otherwise as `PublishedEvent` (`name`, `revision`, JSON text). A typed handler is not called for a `PublishedEvent`: keep one that is, when the reaction must not miss a newer revision.
- Nullable state (`StateEvent<View?>`) when the upstream publishes no state (`publishState = false`).
- Needs `wow-kafka`, `wow.kafka.bootstrap-servers`, and the upstream's `*-api` module on the class path.
- Start: a projection from the beginning, so a new subscriber reads the history; a reactor's or a saga's handler where the service joins the stream, from the beginning when no other subscription of the service has read it yet, else from the end, so a handler added later does not act on the history; one that must not act on history says `start = OnEvent.Start.END` (and the review checks one that must not miss history and starts at the end).

## Sending another context commands

- A saga returns a `CommandRequest` as for its own context; the target's `*-api` module on the class path carries the contract that names the target aggregate.
- Transport: the command goes to the target's command topic `wow.<target>.commands`; the target runs with `wow.kafka.command-intake.enabled: true` and its command topic must exist with exactly `wow.log.partitions` partitions. Rejections and commits return as receipts on `wow.<sender>.receipts`, to `@OnRejected` and `@OnCommitted`.
- A node without a command transport refuses to start when a saga sends, schedules or handles receipts of another context's commands (`Node … has no command transport …`). Production needs Kafka; a test server (`wowServer`) and a local run on the loopback interface keep the topics in their JVM with no configuration (`wow.transport=auto` in development), and `wow.transport=memory` keeps them there on purpose. Several services run in one JVM, sharing those topics, with `runWowLocal { service<App>(name, contexts, publicPort, internalPort) }` (`wow-test-spring-boot`), a called service listed before its callers.

## Calling another context synchronously

When the saga needs the answer before it goes on (`SagaContext.call`, `suspend`): declare it with `@Sends(calls = [SubmitOrder::class])` on the handler; an undeclared call fails the handling as unrecoverable, and a rejection the handler lets through goes to the saga's `@OnRejected` handler of the called command. The node does not start when a called command is its own context's (derive it instead), when no Manifest decides it, or when the node's caller cannot reach its context: `wow.peers.<context>.url` sets the address, else `wow.peer.discovery` (DNS SRV records by default) finds its service. Prefer an asynchronous command and a receipt; a call couples the two contexts' availability.

## One tenant space

Tenant ids cross contexts as they are: a command a saga derives is in the tenant of the commit it answers, unless it names another.

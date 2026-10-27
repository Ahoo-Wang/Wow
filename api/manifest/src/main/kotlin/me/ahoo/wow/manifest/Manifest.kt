/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.manifest

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The contract of a service: its bounded contexts, their aggregates with the
 * commands and events they speak, and their subscriptions, with the JSON Schema 2020-12 of every payload in [defs]
 * (written `$defs`).
 *
 * The same type holds a module's **fragment** (`META-INF/wow/manifest/<module>.json`, without [service]) and a
 * service's merged Manifest ([ManifestMerger]). It is plain data: [ManifestTree] turns it into the JSON tree any
 * JSON library writes, and reads it back.
 *
 * Names inside are relative to their parent: a command named `AddItem` of aggregate `cart` in context `shop` is the
 * command type `shop.cart.AddItem`. A schema is referred to as `#/$defs/<key>` ([schemaRef]).
 *
 * [errors] lists the framework's `wow.*` error codes with their HTTP statuses ([FrameworkErrors]): a
 * fragment leaves it empty and [ManifestMerger] writes the table.
 *
 * A command another part of the Manifest refers to ([SubscriptionManifest.derives], [SubscriptionManifest.rejected],
 * [SubscriptionManifest.committed], [SubscriptionManifest.calls]) is named by its full type name (`shop.cart.RemoveCartItem`) when the module that
 * wrote it decides the command; else
 * by the schema reference of its class (`#/$defs/<binary name>`, [schemaRef]): [defs] holds its schema like any other,
 * and the Manifest of the context that decides it (a peer Manifest) names it by the same schema.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class Manifest @InternalWowApi constructor(
    val format: Int = FORMAT,
    val service: ServiceInfo? = null,
    val contexts: List<ContextManifest> = emptyList(),
    val errors: List<ErrorManifest> = emptyList(),
    /** The JSON Schemas the [schemaRef]s point to, by key, as JSON trees ([ManifestTree] describes the values). */
    val defs: Map<String, Any?> = emptyMap(),
) {
    /**
     * Every schema reference of the contexts, in declaration order: those of the aggregates, then the commands the
     * subscriptions refer to by their schemas.
     */
    val schemaRefs: List<String>
        get() = contexts.flatMap { context ->
            context.aggregates.flatMap { aggregate ->
                listOf(aggregate.state.schema) + aggregate.commands.map { it.schema } + aggregate.events.map { it.schema }
            } + context.subscriptions.flatMap { subscription ->
                val commands = subscription.derives.orEmpty() + subscription.rejected.orEmpty() +
                    subscription.committed.orEmpty() + subscription.calls.orEmpty()
                commands.filter { it.startsWith(DEFS_PREFIX) }
            }
        }

    /** The schema references whose key is not in [defs]: a fragment before its schemas are generated has them all. */
    val unresolvedSchemaRefs: List<String>
        get() = schemaRefs.distinct().filter { schemaKey(it) !in defs }

    override fun equals(other: Any?): Boolean =
        this === other || other is Manifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "Manifest(format=$format, service=$service, contexts=$contexts, errors=$errors, defs=$defs)"

    /** This Manifest with [contexts] in place of its own. */
    fun withContexts(contexts: List<ContextManifest>): Manifest = Manifest(format, service, contexts, errors, defs)

    /** This Manifest with [defs] in place of its own. */
    fun withDefs(defs: Map<String, Any?>): Manifest = Manifest(format, service, contexts, errors, defs)

    private fun valueFields(): List<Any?> = listOf(format, service, contexts, errors, defs)

    companion object {
        /** The format version of this structure; a reader refuses another. */
        const val FORMAT: Int = 1

        /** Where a module's fragment lives on the classpath: `META-INF/wow/manifest/<module>.json`. */
        const val FRAGMENT_DIRECTORY: String = "META-INF/wow/manifest"

        private const val DEFS_PREFIX = "#/\$defs/"

        /** The reference to the schema [key] of `$defs`. */
        fun schemaRef(key: String): String = DEFS_PREFIX + key

        /** The key of `$defs` [ref] points to. */
        fun schemaKey(ref: String): String {
            require(ref.startsWith(DEFS_PREFIX)) { "A schema reference points into \$defs: $ref" }
            return ref.removePrefix(DEFS_PREFIX)
        }
    }
}

/**
 * The service a merged Manifest describes; a fragment has none, and a peer contract names the service that hosts its
 * contexts, by its name alone, when its module declares it.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class ServiceInfo @InternalWowApi constructor(val name: String, val version: String? = null) {
    override fun equals(other: Any?): Boolean =
        this === other || other is ServiceInfo && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ServiceInfo(name=$name, version=$version)"

    private fun valueFields(): List<Any?> = listOf(name, version)
}

/**
 * A bounded context. [errors] optionally documents the domain's rejection codes, the `@ErrorCode` constants of its
 * module; they are runtime strings, so the list is neither complete nor checked against what the code
 * rejects with, and a `wow.*` code is not the context's.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class ContextManifest @InternalWowApi constructor(
    val name: String,
    val description: String? = null,
    val aggregates: List<AggregateManifest> = emptyList(),
    val subscriptions: List<SubscriptionManifest> = emptyList(),
    val published: List<PublishedManifest> = emptyList(),
    val errors: List<ErrorManifest> = emptyList(),
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is ContextManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ContextManifest(name=$name, description=$description, aggregates=$aggregates, " +
            "subscriptions=$subscriptions, published=$published, errors=$errors)"

    /** This ContextManifest with [aggregates] in place of its own. */
    fun withAggregates(aggregates: List<AggregateManifest>): ContextManifest =
        ContextManifest(name, description, aggregates, subscriptions, published, errors)

    private fun valueFields(): List<Any?> = listOf(name, description, aggregates, subscriptions, published, errors)
}

/**
 * An aggregate of a context. [lifecycle] lists the system commands the aggregate's model handles, by
 * their full names in the system namespace (`wow.lifecycle.DeleteAggregate`): they are the same types for every
 * aggregate, so unlike [commands] they are not names within the aggregate and never conflict between aggregates.
 * The body of each is the schema of [Manifest.defs] keyed by that full name ([Manifest.schemaRef] of it), so a client
 * types it like a command's.
 * [queries] are the read models of the aggregate a client queries ([QueryManifest]).
 *
 * [schedules] names the commands the aggregate's decide functions may schedule, each by its full type
 * name (`context.aggregate.Command`), as their `@OnCommand(schedules)` declare; null when none declares any.
 *
 * [ownership] is who the aggregate belongs to besides its tenant, as `@AggregateRoot(ownership)` declares it:
 * query admission restricts an owner-level caller's queries of an [Ownership.OWNER] aggregate to its owner, and
 * not those of an [Ownership.TENANT] one.
 *
 * [retired] lists the events the aggregate retired, as `@AggregateRoot(retiredEvents)` declares them:
 * the stored names whose records are skipped, as [EventManifest.aliases] writes names (without a dot
 * within the aggregate, else full). The compatibility gate takes an event removed and retired for a retirement, and
 * the data migration checks a mapping to a retired name against it.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class AggregateManifest @InternalWowApi constructor(
    val name: String,
    val description: String? = null,
    val state: StateManifest,
    val snapshot: SnapshotManifest = SnapshotManifest(),
    val commands: List<CommandManifest> = emptyList(),
    val events: List<EventManifest> = emptyList(),
    val lifecycle: List<String> = emptyList(),
    val queries: List<QueryManifest> = emptyList(),
    val schedules: List<String>? = null,
    val ownership: Ownership = Ownership.OWNER,
    val retired: List<String> = emptyList(),
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is AggregateManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "AggregateManifest(name=$name, description=$description, state=$state, snapshot=$snapshot, " +
            "commands=$commands, events=$events, lifecycle=$lifecycle, queries=$queries, schedules=$schedules, " +
            "ownership=$ownership, retired=$retired)"

    /** This AggregateManifest with [state] in place of its own. */
    fun withState(state: StateManifest): AggregateManifest =
        AggregateManifest(
            name,
            description,
            state,
            snapshot,
            commands,
            events,
            lifecycle,
            queries,
            schedules,
            ownership,
            retired,
        )

    /** This AggregateManifest with [commands] in place of its own. */
    fun withCommands(commands: List<CommandManifest>): AggregateManifest =
        AggregateManifest(
            name,
            description,
            state,
            snapshot,
            commands,
            events,
            lifecycle,
            queries,
            schedules,
            ownership,
            retired,
        )

    /** This AggregateManifest with [queries] in place of its own. */
    fun withQueries(queries: List<QueryManifest>): AggregateManifest =
        AggregateManifest(
            name,
            description,
            state,
            snapshot,
            commands,
            events,
            lifecycle,
            queries,
            schedules,
            ownership,
            retired,
        )

    private fun valueFields(): List<Any?> = listOf(
        name,
        description,
        state,
        snapshot,
        commands,
        events,
        lifecycle,
        queries,
        schedules,
        ownership,
        retired,
    )
}

/** Who an aggregate belongs to besides its tenant (`me.ahoo.wow.api.aggregate.Ownership`). */
enum class Ownership {
    /** The owner of the command that creates it, when that command has one. */
    OWNER,

    /** Its tenant alone: no owner is recorded, and no read is restricted by owner. */
    TENANT,
}

/**
 * The aggregate's state: its [schema], and the [fingerprint] a snapshot written under it carries;
 * the fingerprint is null until the schemas are generated.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class StateManifest @InternalWowApi constructor(val schema: String, val fingerprint: String? = null) {
    override fun equals(other: Any?): Boolean =
        this === other || other is StateManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "StateManifest(schema=$schema, fingerprint=$fingerprint)"

    /** This StateManifest with [fingerprint] in place of its own. */
    fun withFingerprint(fingerprint: String?): StateManifest = StateManifest(schema, fingerprint)

    private fun valueFields(): List<Any?> = listOf(schema, fingerprint)
}

/**
 * Whether the aggregate keeps snapshots ([enabled]), and the revision of its state's structure
 * the snapshots are written at ([stateRevision]): raised, the service rebuilds the stored snapshots in
 * place, and the compatibility gate accepts a state schema change of an aggregate whose state no one else reads (no
 * snapshot query, no published stream with the state).
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class SnapshotManifest @InternalWowApi constructor(val enabled: Boolean = true, val stateRevision: Int = 1) {
    init {
        require(stateRevision >= 1) { "A state revision starts at 1." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is SnapshotManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "SnapshotManifest(enabled=$enabled, stateRevision=$stateRevision)"

    private fun valueFields(): List<Any?> = listOf(enabled, stateRevision)
}

/**
 * A query model of an aggregate: [model] is [QueryRoutes.SNAPSHOT], the aggregate's snapshots,
 * when it keeps them, or [QueryRoutes.EVENTS], its event streams; [routes] are the model's query route and the route
 * of its capability descriptor ([QueryRoutes]). What a model can do (its operations, fields, limits) is not here: it
 * depends on the query backend the service runs on, which the build does not know, and the
 * descriptor route answers it.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class QueryManifest @InternalWowApi constructor(val model: String, val routes: List<RouteManifest> = emptyList()) {
    override fun equals(other: Any?): Boolean =
        this === other || other is QueryManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "QueryManifest(model=$model, routes=$routes)"

    private fun valueFields(): List<Any?> = listOf(model, routes)
}

/**
 * A command the aggregate decides, or one [mounted] under its routes.
 *
 * [aliases] are former names, as `@Alias` declares them: one without a dot is within the aggregate, one with dots a
 * full type name. [produces] names the events of the aggregate the command's decide function and after hooks can
 * produce, as their signatures say or `@OnCommand(produces)` declares; null when neither says (a
 * `Decision`, `Any`). [routes] are the command's REST routes ([CommandRoutes]).
 *
 * A [mounted] command (`@MountedCommand`) is decided by no aggregate: a transformer of the service turns
 * it into a command that is, at admission. It creates nothing itself ([creation] is [Creation.NONE]) and produces
 * nothing ([produces] is null); [transformsTo] lists, by full type name, the commands it becomes, as
 * `@MountedCommand(transformsTo)` declares them; null when it declares none.
 *
 * [description] is what the command's class says it does: its `@Description`, else the summary of its KDoc; null when
 * it says nothing. It documents the command for a client, and is no part of the contract.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class CommandManifest @InternalWowApi constructor(
    val name: String,
    val aliases: List<String> = emptyList(),
    val schema: String,
    val creation: Creation = Creation.NONE,
    val produces: List<String>? = null,
    val routes: List<RouteManifest> = emptyList(),
    val mounted: Boolean = false,
    val transformsTo: List<String>? = null,
    val description: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is CommandManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "CommandManifest(name=$name, aliases=$aliases, schema=$schema, creation=$creation, produces=$produces, " +
            "routes=$routes, mounted=$mounted, transformsTo=$transformsTo, description=$description)"

    /** This CommandManifest with [routes] in place of its own. */
    fun withRoutes(routes: List<RouteManifest>): CommandManifest =
        CommandManifest(name, aliases, schema, creation, produces, routes, mounted, transformsTo, description)

    private fun valueFields(): List<Any?> = listOf(
        name,
        aliases,
        schema,
        creation,
        produces,
        routes,
        mounted,
        transformsTo,
        description,
    )
}

/**
 * An HTTP route: its [method] and its [path], a template whose variables are written literally in braces
 * (`/shop/cart/{id}/add-item`).
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class RouteManifest @InternalWowApi constructor(val method: String, val path: String) {
    override fun equals(other: Any?): Boolean =
        this === other || other is RouteManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "RouteManifest(method=$method, path=$path)"

    private fun valueFields(): List<Any?> = listOf(method, path)
}

/**
 * An error code a client may receive: its [code], the HTTP [status] it is answered with, and what it means. The
 * code is the `code` of a `CommandResult` and of a problem+json body, whose `type` is `urn:wow:error:<code>`.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class ErrorManifest @InternalWowApi constructor(val code: String, val status: Int, val description: String? = null) {
    override fun equals(other: Any?): Boolean =
        this === other || other is ErrorManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ErrorManifest(code=$code, status=$status, description=$description)"

    private fun valueFields(): List<Any?> = listOf(code, status, description)
}

/** Whether a command creates its aggregate (`@CreateAggregate`, `@AllowCreate`). */
enum class Creation {
    /** Only on an aggregate that exists. */
    NONE,

    /** Only on an aggregate that does not exist yet. */
    REQUIRED,

    /** On either. */
    ALLOWED,
}

/**
 * An event of the aggregate: its name in the log, its [revision] and former names, and its
 * [description], as for a [CommandManifest].
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class EventManifest @InternalWowApi constructor(
    val name: String,
    val aliases: List<String> = emptyList(),
    val revision: Int = 1,
    val schema: String,
    val description: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is EventManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "EventManifest(name=$name, aliases=$aliases, revision=$revision, schema=$schema, description=$description)"

    private fun valueFields(): List<Any?> = listOf(name, aliases, revision, schema, description)
}

/**
 * A subscription: one handler of a processor, named `processor.function`.
 *
 * - [derives] names the commands a saga's handler may derive: those `@OnEvent(derives)` declares, else
 *   those its return type names (`Command<X>`, a list of them); null when neither says (a `Command<*>`), and always
 *   for a projection or a reactor. When it is set, a wait chain may follow the saga's hop only into the contexts of
 *   these commands.
 * - [rejected] is set on an `@OnRejected` handler of a saga and on no other: the commands whose
 *   rejections it handles, the one its `CommandRejected<C>` names, or empty for any command the saga derives. Such a
 *   handler reads rejections, not state events: its [source] is empty and not read.
 * - [committed] is set on an `@OnCommitted` handler of a saga and on no other: the commands whose
 *   success receipts it handles, the one its `CommandCommitted<C>` names, or empty for any command the saga derives.
 *   Like an `@OnRejected` handler, it reads receipts ([isReceiptHandler]), not state events.
 * - [start] is where a new subscription starts in the log: what `@OnEvent(start)` declares,
 *   else [SubscriptionStart.defaultFor] its kind; the host may configure another. Null on an `@OnRejected` or
 *   `@OnCommitted` handler, which reads no log, and on a reactor's or a saga's handler of another context's stream,
 *   which starts where its service joins the stream: from the beginning when no other subscription of the service
 *   reads it yet, else from the end.
 * - [schedules] names the commands a saga's handler may schedule, each by its full type name, as its
 *   `@OnEvent(schedules)` declares; null when it declares none, and always for a projection or a reactor.
 * - [retry] is the handler's own in-process retry budget, as its `@Retry` and its processor's declare it:
 *   null when neither does. The host's `wow.subscriptions.[<name>].retry.*` overrides it, setting by setting.
 * - [job] is the job of a saga's `@OnSchedule` handler: its subscription reads the context's built-in
 *   job aggregate `<context>.schedule` ([source]) and hands its job's ticks to the handler. Null for an `@OnEvent`
 *   handler.
 * - [calls] names the commands a saga's `@OnEvent` handler sends to another context with `SagaContext.call`,
 *   as its `@OnEvent(calls)` declares, each referred to as [derives] refers to a command; null when it
 *   declares none. A wait chain may follow the saga's hop into their contexts too, but they need no command topic:
 *   the node calls their gateways, at the addresses its host configures.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class SubscriptionManifest @InternalWowApi constructor(
    val name: String,
    val kind: SubscriptionKind,
    val source: SubscriptionSource,
    val function: FunctionManifest,
    val derives: List<String>? = null,
    val rejected: List<String>? = null,
    val start: SubscriptionStart? = null,
    val schedules: List<String>? = null,
    val retry: RetryManifest? = null,
    val committed: List<String>? = null,
    val job: JobManifest? = null,
    val calls: List<String>? = null,
) {
    /** Whether it is a saga's `@OnRejected` or `@OnCommitted` handler: it reads receipts, not the log. */
    val isReceiptHandler: Boolean get() = rejected != null || committed != null

    override fun equals(other: Any?): Boolean =
        this === other || other is SubscriptionManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "SubscriptionManifest(name=$name, kind=$kind, source=$source, function=$function, derives=$derives, " +
            "rejected=$rejected, start=$start, schedules=$schedules, retry=$retry, committed=$committed, job=$job, calls=$calls)"

    private fun valueFields(): List<Any?> = listOf(
        name,
        kind,
        source,
        function,
        derives,
        rejected,
        start,
        schedules,
        retry,
        committed,
        job,
        calls,
    )
}

/**
 * A job of an `@OnSchedule` handler: its [name], its default rule ([cron] read in [zone], missed slots
 * handled as [misfire] says: `COALESCE` or `CATCH_UP`), its [scope] (`SYSTEM` or `TENANT`) and whether it is [sharded].
 * The host's `wow.schedules.<name>.*` overrides the rule.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class JobManifest @InternalWowApi constructor(
    val name: String,
    val cron: String,
    val zone: String = "UTC",
    val scope: String = "SYSTEM",
    val sharded: Boolean = false,
    val misfire: String = "COALESCE",
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is JobManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "JobManifest(name=$name, cron=$cron, zone=$zone, scope=$scope, sharded=$sharded, misfire=$misfire)"

    private fun valueFields(): List<Any?> = listOf(name, cron, zone, scope, sharded, misfire)
}

/**
 * A subscription handler's in-process retry budget as `@Retry` declares it: each setting null when it
 * is not given. Durations are ISO-8601 (`PT2S`). [recoverable] and [unrecoverable] are the qualified names of the
 * exception classes the handler classifies itself (`@Retry(recoverable, unrecoverable)`).
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class RetryManifest @InternalWowApi constructor(
    val maxAttempts: Int? = null,
    val initialBackoff: String? = null,
    val maxBackoff: String? = null,
    val timeout: String? = null,
    val recoverable: List<String>? = null,
    val unrecoverable: List<String>? = null,
    /** How many times longer each wait between attempts is than the one before. */
    val multiplier: Double? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is RetryManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "RetryManifest(maxAttempts=$maxAttempts, initialBackoff=$initialBackoff, maxBackoff=$maxBackoff, " +
            "timeout=$timeout, recoverable=$recoverable, unrecoverable=$unrecoverable, multiplier=$multiplier)"

    private fun valueFields(): List<Any?> =
        listOf(maxAttempts, initialBackoff, maxBackoff, timeout, recoverable, unrecoverable, multiplier)
}

enum class SubscriptionKind { PROJECTION, REACTOR, SAGA }

/** Where a new subscription, one without a checkpoint, starts reading the log. */
enum class SubscriptionStart {
    /** From the first entry: a projection builds its read model from the whole history. */
    BEGINNING,

    /** From the entries committed after it starts: a reactor or a saga does not act on the history again. */
    END,
    ;

    companion object {
        /** A projection starts from the [BEGINNING], a reactor or a saga from the [END]. */
        fun defaultFor(kind: SubscriptionKind): SubscriptionStart =
            if (kind == SubscriptionKind.PROJECTION) BEGINNING else END
    }
}

/**
 * What a subscription reads: the aggregates (by name) of [context] whose state events it receives, and the event
 * names it is limited to. Empty [aggregates] or [events] mean no limit; a null [context] means the handler's state
 * type is not an aggregate of the module that declares it.
 *
 * [context] may be another context than the subscription's (`@OnEvent(aggregates = ["ctx.agg"])`):
 * the subscription then reads that context's published state events ([PublishedManifest]) instead of the log.
 *
 * [nullableState] says the handler takes a null state, `StateEvent<S?>` or `StateEvent<*>`: only such
 * a handler may read a stream published without the state (`PublishedManifest.includeState = false`).
 *
 * [eventSchemas] is a fragment's alone: the schemas of another context's events a handler takes by type
 * (`StateTypedEvent<V, E>`) without naming their aggregate. The service's Manifest finds the context and the aggregates
 * from the peer contracts on its class path ([PeerSources]) and holds those instead.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class SubscriptionSource @InternalWowApi constructor(
    val context: String? = null,
    val aggregates: List<String> = emptyList(),
    val events: List<String> = emptyList(),
    val nullableState: Boolean = false,
    val eventSchemas: List<String> = emptyList(),
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is SubscriptionSource && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "SubscriptionSource(context=$context, aggregates=$aggregates, events=$events, nullableState=$nullableState, " +
            "eventSchemas=$eventSchemas)"

    private fun valueFields(): List<Any?> = listOf(context, aggregates, events, nullableState, eventSchemas)
}

/**
 * A stream of state events a context publishes for other contexts: those of its [aggregate], on
 * the Kafka [topic] ([defaultTopic] unless overridden), each with the aggregate's state after the commit unless
 * [includeState] is false, for an aggregate whose state is too large (a subscriber of such a stream
 * declares a nullable state).
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class PublishedManifest @InternalWowApi constructor(
    val aggregate: String,
    val topic: String,
    val includeState: Boolean = true,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is PublishedManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "PublishedManifest(aggregate=$aggregate, topic=$topic, includeState=$includeState)"

    private fun valueFields(): List<Any?> = listOf(aggregate, topic, includeState)

    companion object {
        /** The topic of the state events of [aggregate] of [context]: `wow.<context>.<aggregate>.state`. */
        fun defaultTopic(context: String, aggregate: String): String = "wow.$context.$aggregate.state"
    }
}

/**
 * The function a subscription runs, as `CommandResult.function` names it.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class FunctionManifest @InternalWowApi constructor(
    val kind: FunctionKind,
    val context: String,
    val processor: String,
    val name: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is FunctionManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "FunctionManifest(kind=$kind, context=$context, processor=$processor, name=$name)"

    private fun valueFields(): List<Any?> = listOf(kind, context, processor, name)
}

/** The kinds of `me.ahoo.wow.api.result.FunctionKind` a Manifest names. */
enum class FunctionKind { COMMAND, PROJECT, REACT, SAGA }

/**
 * A fragment and where it was read from, for the messages of [ManifestMerger].
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class ManifestFragment @InternalWowApi constructor(val source: String, val manifest: Manifest) {
    override fun equals(other: Any?): Boolean =
        this === other || other is ManifestFragment && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ManifestFragment(source=$source, manifest=$manifest)"

    private fun valueFields(): List<Any?> = listOf(source, manifest)
}

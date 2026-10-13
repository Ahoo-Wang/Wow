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

/**
 * The contract of a service (design 05 §2, decisions D17, D73, D74): its bounded contexts, their aggregates with the
 * commands and events they speak, and their subscriptions, with the JSON Schema 2020-12 of every payload in [defs]
 * (written `$defs`).
 *
 * The same type holds a module's **fragment** (`META-INF/wow/manifest/<module>.json`, without [service]) and a
 * service's merged Manifest ([ManifestMerger]). It is plain data: [ManifestTree] turns it into the JSON tree any
 * JSON library writes, and reads it back.
 *
 * Names inside are relative to their parent: a command named `AddItem` of aggregate `cart` in context `shop` is the
 * command type `shop.cart.AddItem` (design 08 §2). A schema is referred to as `#/$defs/<key>` ([schemaRef]).
 *
 * [errors] lists the framework's `wow.*` error codes with their HTTP statuses ([FrameworkErrors], decision D102): a
 * fragment leaves it empty and [ManifestMerger] writes the table.
 *
 * A command another part of the Manifest refers to ([SubscriptionManifest.derives], [SubscriptionManifest.rejected])
 * is named by its full type name (`shop.cart.RemoveCartItem`) when the module that wrote it decides the command; else
 * by the schema reference of its class (`#/$defs/<binary name>`, [schemaRef]): [defs] holds its schema like any other,
 * and the Manifest of the context that decides it (a peer Manifest, decision D139) names it by the same schema.
 */
data class Manifest(
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
                (subscription.derives.orEmpty() + subscription.rejected.orEmpty()).filter { it.startsWith(DEFS_PREFIX) }
            }
        }

    /** The schema references whose key is not in [defs]: a fragment before its schemas are generated has them all. */
    val unresolvedSchemaRefs: List<String>
        get() = schemaRefs.distinct().filter { schemaKey(it) !in defs }

    companion object {
        /** The format version of this structure (decision D59); a reader refuses another. */
        const val FORMAT: Int = 1

        /** Where a module's fragment lives on the classpath: `META-INF/wow/manifest/<module>.json` (decision D74). */
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

/** The service a merged Manifest describes; a fragment has none. */
data class ServiceInfo(val name: String, val version: String? = null)

/**
 * A bounded context. [errors] optionally documents the domain's rejection codes, the `@ErrorCode` constants of its
 * module (decision D121); they are runtime strings, so the list is neither complete nor checked against what the code
 * rejects with, and a `wow.*` code is not the context's.
 */
data class ContextManifest(
    val name: String,
    val description: String? = null,
    val aggregates: List<AggregateManifest> = emptyList(),
    val subscriptions: List<SubscriptionManifest> = emptyList(),
    val published: List<PublishedManifest> = emptyList(),
    val errors: List<ErrorManifest> = emptyList(),
)

/**
 * An aggregate of a context. [lifecycle] lists the system commands (design 01 §6) the aggregate's model handles, by
 * their full names in the system namespace (`wow.lifecycle.DeleteAggregate`): they are the same types for every
 * aggregate, so unlike [commands] they are not names within the aggregate and never conflict between aggregates.
 * [queries] are the read models of the aggregate a client queries ([QueryManifest], decision D128).
 *
 * [schedules] names the commands the aggregate's decide functions may schedule (decision D166), each by its full type
 * name (`context.aggregate.Command`), as their `@OnCommand(schedules)` declare; null when none declares any.
 */
data class AggregateManifest(
    val name: String,
    val description: String? = null,
    val state: StateManifest,
    val snapshot: SnapshotManifest = SnapshotManifest(),
    val commands: List<CommandManifest> = emptyList(),
    val events: List<EventManifest> = emptyList(),
    val lifecycle: List<String> = emptyList(),
    val queries: List<QueryManifest> = emptyList(),
    val schedules: List<String>? = null,
)

/**
 * The aggregate's state: its [schema], and the [fingerprint] a snapshot written under it carries (design 03 §3);
 * the fingerprint is null until the schemas are generated.
 */
data class StateManifest(val schema: String, val fingerprint: String? = null)

data class SnapshotManifest(val enabled: Boolean = true)

/**
 * A query model of an aggregate (decisions D127, D128): [model] is [QueryRoutes.SNAPSHOT], the aggregate's snapshots,
 * when it keeps them, or [QueryRoutes.EVENTS], its event streams; [routes] are the model's query route and the route
 * of its capability descriptor ([QueryRoutes]). What a model can do (its operations, fields, limits) is not here: it
 * depends on the query backend the service runs on, which the build does not know (decision D110), and the
 * descriptor route answers it.
 */
data class QueryManifest(val model: String, val routes: List<RouteManifest> = emptyList())

/**
 * A command the aggregate decides, or one [mounted] under its routes.
 *
 * [aliases] are former names, as `@Alias` declares them: one without a dot is within the aggregate, one with dots a
 * full type name. [produces] names the events of the aggregate the command's decide function and after hooks can
 * produce, as their signatures say or `@OnCommand(produces)` declares (decision D120); null when neither says (a
 * `Decision`, `Any`). [routes] are the command's REST routes ([CommandRoutes], decision D104).
 *
 * A [mounted] command (`@MountedCommand`, decision D182) is decided by no aggregate: a transformer of the service turns
 * it into a command that is, at admission. It creates nothing itself ([creation] is [Creation.NONE]) and produces
 * nothing ([produces] is null); [transformsTo] lists, by full type name, the commands it becomes, as
 * `@MountedCommand(transformsTo)` declares them; null when it declares none.
 */
data class CommandManifest(
    val name: String,
    val aliases: List<String> = emptyList(),
    val schema: String,
    val creation: Creation = Creation.NONE,
    val produces: List<String>? = null,
    val routes: List<RouteManifest> = emptyList(),
    val mounted: Boolean = false,
    val transformsTo: List<String>? = null,
)

/**
 * An HTTP route: its [method] and its [path], a template whose variables are written literally in braces
 * (`/shop/cart/{id}/add-item`).
 */
data class RouteManifest(val method: String, val path: String)

/**
 * An error code a client may receive: its [code], the HTTP [status] it is answered with, and what it means. The
 * code is the `code` of a `CommandResult` and of a problem+json body, whose `type` is `urn:wow:error:<code>`
 * (decision D101).
 */
data class ErrorManifest(val code: String, val status: Int, val description: String? = null)

/** Whether a command creates its aggregate (`@CreateAggregate`, `@AllowCreate`). */
enum class Creation {
    /** Only on an aggregate that exists. */
    NONE,

    /** Only on an aggregate that does not exist yet. */
    REQUIRED,

    /** On either. */
    ALLOWED,
}

/** An event of the aggregate: its name in the log, its [revision] and former names (design 01 §8). */
data class EventManifest(
    val name: String,
    val aliases: List<String> = emptyList(),
    val revision: Int = 1,
    val schema: String,
)

/**
 * A subscription: one handler of a processor, named `processor.function` (design 04 §1).
 *
 * - [derives] names the commands a saga's handler may derive (decision D138): those `@OnEvent(derives)` declares, else
 *   those its return type names (`Command<X>`, a list of them); null when neither says (a `Command<*>`), and always
 *   for a projection or a reactor. When it is set, a wait chain may follow the saga's hop only into the contexts of
 *   these commands.
 * - [rejected] is set on an `@OnRejected` handler of a saga (decisions D136, D153) and on no other: the commands whose
 *   rejections it handles, the one its `CommandRejected<C>` names, or empty for any command the saga derives. Such a
 *   handler reads rejections, not state events: its [source] is empty and not read.
 * - [start] is where a new subscription starts in the log (decision D154): [SubscriptionStart.defaultFor] its kind,
 *   unless the host configures another. Null on an `@OnRejected` handler, which reads no log.
 * - [schedules] names the commands a saga's handler may schedule (decision D166), each by its full type name, as its
 *   `@OnEvent(schedules)` declares; null when it declares none, and always for a projection or a reactor.
 */
data class SubscriptionManifest(
    val name: String,
    val kind: SubscriptionKind,
    val source: SubscriptionSource,
    val function: FunctionManifest,
    val derives: List<String>? = null,
    val rejected: List<String>? = null,
    val start: SubscriptionStart? = null,
    val schedules: List<String>? = null,
)

enum class SubscriptionKind { PROJECTION, REACTOR, SAGA }

/** Where a new subscription, one without a checkpoint, starts reading the log (decision D154). */
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
 * [context] may be another context than the subscription's (`@OnEvent(aggregates = ["ctx.agg"])`, decision D138):
 * the subscription then reads that context's published state events ([PublishedManifest]) instead of the log.
 *
 * [nullableState] says the handler takes a null state, `StateEvent<S?>` or `StateEvent<*>` (decision D140): only such
 * a handler may read a stream published without the state (`PublishedManifest.includeState = false`).
 */
data class SubscriptionSource(
    val context: String? = null,
    val aggregates: List<String> = emptyList(),
    val events: List<String> = emptyList(),
    val nullableState: Boolean = false,
)

/**
 * A stream of state events a context publishes for other contexts (decisions D58, D135): those of its [aggregate], on
 * the Kafka [topic] ([defaultTopic] unless overridden), each with the aggregate's state after the commit unless
 * [includeState] is false, for an aggregate whose state is too large (decision D140: a subscriber of such a stream
 * declares a nullable state).
 */
data class PublishedManifest(
    val aggregate: String,
    val topic: String,
    val includeState: Boolean = true,
) {
    companion object {
        /** The topic of the state events of [aggregate] of [context]: `wow.<context>.<aggregate>.state`. */
        fun defaultTopic(context: String, aggregate: String): String = "wow.$context.$aggregate.state"
    }
}

/** The function a subscription runs, as `CommandResult.function` names it. */
data class FunctionManifest(
    val kind: FunctionKind,
    val context: String,
    val processor: String,
    val name: String,
)

/** The kinds of `me.ahoo.wow.api.result.FunctionKind` a Manifest names. */
enum class FunctionKind { COMMAND, PROJECT, REACT, SAGA }

/** A fragment and where it was read from, for the messages of [ManifestMerger]. */
data class ManifestFragment(val source: String, val manifest: Manifest)

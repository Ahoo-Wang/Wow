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
 * v0 holds what the model declares today; errors, queries, published streams, lifecycle commands and routes come
 * with the parts of V10 that define them.
 */
data class Manifest(
    val format: Int = FORMAT,
    val service: ServiceInfo? = null,
    val contexts: List<ContextManifest> = emptyList(),
    /** The JSON Schemas the [schemaRef]s point to, by key, as JSON trees ([ManifestTree] describes the values). */
    val defs: Map<String, Any?> = emptyMap(),
) {
    /** Every schema reference of the contexts, in declaration order. */
    val schemaRefs: List<String>
        get() = contexts.flatMap { context ->
            context.aggregates.flatMap { aggregate ->
                listOf(aggregate.state.schema) + aggregate.commands.map { it.schema } + aggregate.events.map { it.schema }
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

data class ContextManifest(
    val name: String,
    val description: String? = null,
    val aggregates: List<AggregateManifest> = emptyList(),
    val subscriptions: List<SubscriptionManifest> = emptyList(),
)

data class AggregateManifest(
    val name: String,
    val description: String? = null,
    val state: StateManifest,
    val snapshot: SnapshotManifest = SnapshotManifest(),
    val commands: List<CommandManifest> = emptyList(),
    val events: List<EventManifest> = emptyList(),
)

/**
 * The aggregate's state: its [schema], and the [fingerprint] a snapshot written under it carries (design 03 §3);
 * the fingerprint is null until the schemas are generated.
 */
data class StateManifest(val schema: String, val fingerprint: String? = null)

data class SnapshotManifest(val enabled: Boolean = true)

/**
 * A command the aggregate decides.
 *
 * [aliases] are former names, as `@Alias` declares them: one without a dot is within the aggregate, one with dots a
 * full type name. [produces] names the events of the aggregate the command's decide function and after hooks can
 * produce, as their signatures say; null when a signature does not say (a `Decision`, `Any`).
 */
data class CommandManifest(
    val name: String,
    val aliases: List<String> = emptyList(),
    val schema: String,
    val creation: Creation = Creation.NONE,
    val produces: List<String>? = null,
)

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

/** A subscription: one handler of a processor, named `processor.function` (design 04 §1). */
data class SubscriptionManifest(
    val name: String,
    val kind: SubscriptionKind,
    val source: SubscriptionSource,
    val function: FunctionManifest,
)

enum class SubscriptionKind { PROJECTION, REACTOR, SAGA }

/**
 * What a subscription reads: the aggregates (by name) of [context] whose state events it receives, and the event
 * names it is limited to. Empty [aggregates] or [events] mean no limit; a null [context] means the handler's state
 * type is not an aggregate of the module that declares it.
 */
data class SubscriptionSource(
    val context: String? = null,
    val aggregates: List<String> = emptyList(),
    val events: List<String> = emptyList(),
)

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

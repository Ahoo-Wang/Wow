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
import me.ahoo.wow.api.problem.Retry

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
 * [requires] lists the markers a reader must understand to read this Manifest as it means ([ManifestFeatures]): a
 * reader that does not know one refuses the Manifest rather than misread it. A value a reader does not know elsewhere
 * is read leniently ([ManifestTree.read]).
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
    /** The markers a reader must understand to read this Manifest ([ManifestFeatures]); none in format 1 so far. */
    val requires: List<String> = emptyList(),
) {
    /**
     * Every schema reference of the contexts, in declaration order: those of the aggregates (their system commands'
     * included), then the context's own system commands', then the commands the subscriptions refer to by their
     * schemas, then the requests and responses of its service routes.
     */
    val schemaRefs: List<String>
        get() = contexts.flatMap { context ->
            context.aggregates.flatMap { aggregate ->
                listOf(aggregate.state.schema) + aggregate.commands.map { it.schema } + aggregate.events.map { it.schema }
            } + context.commands.map { it.schema } + context.subscriptions.flatMap { subscription ->
                val commands = subscription.derives.orEmpty() + subscription.rejected.orEmpty() +
                    subscription.committed.orEmpty() + subscription.calls.orEmpty()
                commands.filter(::isSchemaRef)
            } + context.routes.flatMap { route -> listOfNotNull(route.request, route.response).flatMap(::refsOf) }
        }

    /** The schema references whose key is not in [defs]: a fragment before its schemas are generated has them all. */
    val unresolvedSchemaRefs: List<String>
        get() = schemaRefs.distinct().filter { schemaKey(it) !in defs }

    override fun equals(other: Any?): Boolean =
        this === other || other is Manifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "Manifest(format=$format, service=$service, contexts=$contexts, errors=$errors, defs=$defs, requires=$requires)"

    private fun valueFields(): List<Any?> = listOf(format, service, contexts, errors, defs, requires)

    companion object {
        /** The format version of this structure; a reader refuses another. */
        const val FORMAT: Int = 1

        /** Where a module's fragment lives on the classpath: `META-INF/wow/manifest/<module>.json`. */
        const val FRAGMENT_DIRECTORY: String = "META-INF/wow/manifest"

        /**
         * Where a module registers, beside its fragment, the model provider the compiler generates, for `ServiceLoader`:
         * a resource's name both the compiler, which writes it, and the service's merge, which reads it, use.
         */
        @InternalWowApi
        const val MODEL_PROVIDERS: String = "META-INF/services/me.ahoo.wow.kernel.ModelProvider"

        private const val DEFS_PREFIX = "#/\$defs/"

        /** The reference to the schema [key] of `$defs`. */
        @InternalWowApi
        fun schemaRef(key: String): String = DEFS_PREFIX + key

        /** Whether [ref] is a reference into `$defs` ([schemaRef]): a command named by its class, say. */
        fun isSchemaRef(ref: String): Boolean = ref.startsWith(DEFS_PREFIX)

        /** The `$defs` references an inline [schema] holds, its own, its items' and its alternatives', in order. */
        @InternalWowApi
        fun refsOf(schema: Map<String, Any?>): List<String> = buildList {
            (schema[REF] as? String)?.let(::add)
            @Suppress("UNCHECKED_CAST")
            listOfNotNull(schema[ITEMS] as? Map<String, Any?>).plus(
                (schema[ANY_OF] as? List<*>).orEmpty().filterIsInstance<Map<String, Any?>>(),
            ).forEach { addAll(refsOf(it)) }
        }

        /** The inline schema of a reference to the schema [key] of `$defs`. */
        @InternalWowApi
        fun refSchema(key: String): Map<String, Any?> = mapOf(REF to schemaRef(key))

        /** The inline schema of an array of [items], of distinct values when [unique]. */
        @InternalWowApi
        fun arraySchema(items: Map<String, Any?>, unique: Boolean = false): Map<String, Any?> = buildMap {
            put(TYPE, "array")
            put(ITEMS, items)
            if (unique) put("uniqueItems", true)
        }

        /**
         * The inline schema of a value of [schema] or `null`, as the completed schemas write a nullable value: `null`
         * among its types, or, for a schema of no type such as a reference, a `null` alternative beside it.
         */
        @InternalWowApi
        fun nullableSchema(schema: Map<String, Any?>): Map<String, Any?> = when (val type = schema[TYPE]) {
            null -> mapOf(ANY_OF to listOf(mapOf(TYPE to NULL), schema))
            is List<*> -> if (NULL in type) schema else schema + (TYPE to type + NULL)
            else -> schema + (TYPE to listOf(type, NULL))
        }

        private const val REF = "\$ref"
        private const val ITEMS = "items"
        private const val ANY_OF = "anyOf"
        private const val TYPE = "type"
        private const val NULL = "null"

        /** The key of `$defs` [ref] points to. */
        fun schemaKey(ref: String): String {
            require(isSchemaRef(ref)) { "A schema reference points into \$defs: $ref" }
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
 * [commands] are the context's own [system][CommandManifest.system] commands, which no aggregate of it names: the
 * commands of its schedule jobs (`wow.schedule.EnableJob`, ...), when a subscription of it runs a job. An aggregate's
 * system commands are among its own [AggregateManifest.commands]. [routes] are its service routes:
 * routes whose handler is the service's own code, neither a command nor a query.
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
    val commands: List<CommandManifest> = emptyList(),
    val routes: List<ServiceRouteManifest> = emptyList(),
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is ContextManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ContextManifest(name=$name, description=$description, aggregates=$aggregates, " +
            "subscriptions=$subscriptions, published=$published, errors=$errors, commands=$commands, routes=$routes)"

    private fun valueFields(): List<Any?> =
        listOf(name, description, aggregates, subscriptions, published, errors, commands, routes)
}

/**
 * An error code a client may receive: its [code], the HTTP [status] it is answered with, what it means, and what a retry
 * may do ([retry]), after [retryAfter] seconds, the `Retry-After` of its problem: one when a retry may get past it, none
 * otherwise. The code is the `code` of a `CommandResult` and of a problem+json body, whose `type` is
 * `urn:wow:error:<code>` and whose `retry` member is [retry]'s.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class ErrorManifest @InternalWowApi constructor(
    val code: String,
    val status: Int,
    val description: String? = null,
    val retry: Retry = Retry.NONE,
    val retryAfter: Int? = null,
) {
    init {
        require((retry == Retry.NONE) == (retryAfter == null)) {
            "$code: a Retry-After is given exactly when a retry may get past the error ($retry, $retryAfter)."
        }
        require(retryAfter == null || retryAfter > 0) { "$code: a Retry-After is at least a second." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is ErrorManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ErrorManifest(code=$code, status=$status, description=$description, retry=$retry, retryAfter=$retryAfter)"

    private fun valueFields(): List<Any?> = listOf(code, status, description, retry, retryAfter)
}

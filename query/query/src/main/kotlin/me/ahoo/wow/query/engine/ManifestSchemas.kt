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

package me.ahoo.wow.query.engine

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.schema.ManifestQuerySchemaSource
import me.ahoo.wow.query.schema.MaskStrategyResolver
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaSource
import java.util.concurrent.ConcurrentHashMap

/**
 * The Manifest's state schemas as the query schema source ([ManifestQuerySchemaSource], decision D37), for each
 * aggregate whose state schema the Manifest carries in `$defs`; the Gradle plugin's manifest-tools writes them
 * (decision D73). An aggregate without one, from a module built with the compiler alone, is queried by its system
 * fields only, and a warning says so once. A model that is no aggregate of the Manifest, such as a dataset whose fields
 * its own definition gives, has none to ask for and is not warned about (DX gap G31).
 */
@InternalWowApi
class ManifestStateSchemas(
    private val manifest: Manifest,
    strategies: MaskStrategyResolver = MaskStrategyResolver.NONE,
) : QuerySchemaSource {
    private val source = ManifestQuerySchemaSource(manifest, strategies)
    private val warned = ConcurrentHashMap.newKeySet<AggregateType>()

    override val priority: Int = source.priority

    override suspend fun load(context: QuerySchemaContext): List<QuerySchemaDeclaration> {
        if (declares(context.aggregateType)) return source.load(context)
        if (aggregateOf(context.aggregateType) != null && warned.add(context.aggregateType)) {
            logger.warn {
                "The Manifest carries no state schema of ${context.aggregateType}: only its system fields are " +
                    "queried. Build its module with the Wow Gradle plugin, which writes the schemas."
            }
        }
        return emptyList()
    }

    /** Whether the Manifest carries the state schema of [type]. */
    fun declares(type: AggregateType): Boolean {
        val aggregate = aggregateOf(type) ?: return false
        return Manifest.schemaKey(aggregate.state.schema) in manifest.defs
    }

    private fun aggregateOf(type: AggregateType) =
        manifest.contexts.firstOrNull { it.name == type.context }?.aggregates?.firstOrNull { it.name == type.name }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}

/** The aggregate types of a Manifest by what it lists of them, within the contexts a node hosts. */
@InternalWowApi
object ManifestTypes {
    /** The aggregate types of [manifest] in [contexts] whose snapshots are queried: it lists their snapshot model (D128). */
    fun snapshots(manifest: Manifest, contexts: Set<String>): List<AggregateType> =
        queried(manifest, contexts, QueryRoutes.SNAPSHOT)

    /** The aggregate types of [manifest] in [contexts] whose events are queried: it lists their `events` model (D235). */
    fun events(manifest: Manifest, contexts: Set<String>): List<AggregateType> =
        queried(manifest, contexts, QueryRoutes.EVENTS)

    /** Every aggregate type of [manifest] in [contexts]. */
    fun hosted(manifest: Manifest, contexts: Set<String>): List<AggregateType> =
        manifest.contexts.filter { it.name in contexts }.flatMap { context ->
            context.aggregates.map { AggregateType(context.name, it.name) }
        }

    /** The aggregate types of [manifest] in [contexts] whose [model] the Manifest lists. */
    fun queried(manifest: Manifest, contexts: Set<String>, model: String): List<AggregateType> =
        manifest.contexts.filter { it.name in contexts }.flatMap { context ->
            context.aggregates.filter { aggregate -> aggregate.queries.any { it.model == model } }
                .map { AggregateType(context.name, it.name) }
        }
}

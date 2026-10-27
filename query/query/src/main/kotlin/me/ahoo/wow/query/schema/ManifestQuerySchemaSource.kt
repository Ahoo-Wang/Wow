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

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.spi.json.query.SnapshotFields
import java.util.concurrent.ConcurrentHashMap

/**
 * The query schema of each aggregate's snapshot model as the Manifest states it (decisions D37, D126, D128): the
 * aggregate's state schema in `$defs` becomes the declaration of `state`, its `x-wow-*` keywords (written by
 * `manifest-tools` from the `:query-api` annotations, #4332) the semantic types, aliases and sensitivity of its fields.
 * Nothing is introspected at run time. The snapshot's system fields come from the model's profile
 * ([SnapshotQueryModelProfile]), not from here.
 *
 * Only the snapshot model's `state` is declared here; the `events` model's `events` is [EventsQuerySchemas]'
 * (decision D235). An aggregate whose snapshots are disabled, or that the Manifest does not hold, has no declaration
 * here.
 *
 * Custom mask strategies (`x-wow-mask.strategy`) are resolved through [strategies]: the runtime does not load classes
 * by name, so an unregistered one fails the schema build.
 */
@InternalWowApi
class ManifestQuerySchemaSource(
    private val manifest: Manifest,
    private val strategies: MaskStrategyResolver = MaskStrategyResolver.NONE,
) : QuerySchemaSource {
    override val priority: Int = QuerySchemaSourcePriority.MANIFEST

    private val declarations = ConcurrentHashMap<AggregateType, List<QuerySchemaDeclaration>>()

    override suspend fun load(context: QuerySchemaContext): List<QuerySchemaDeclaration> {
        if (context.model != QueryModel.SNAPSHOT) return emptyList()
        return declarations.computeIfAbsent(context.aggregateType, ::declare)
    }

    /** The declaration of [type]'s snapshot `state`, compiled once; empty when the Manifest declares no such model. */
    fun declare(type: AggregateType): List<QuerySchemaDeclaration> {
        val aggregate = manifest.aggregate(type)?.takeIf { it.snapshot.enabled } ?: return emptyList()
        val reader = ManifestSchemaReader(manifest.defs, type.context, strategies)
        val state = reader.declaration(mapOf(REF to aggregate.state.schema), QueryField(SnapshotFields.STATE))
        // The system field states the payload's nullability and presence; the state schema states its structure.
        val structure = state.copy(nullable = DeclarationValue.Unset, required = DeclarationValue.Unset)
        return listOf(QuerySchemaDeclaration(mapOf(QueryField(SnapshotFields.STATE) to structure)))
    }

    private fun Manifest.aggregate(type: AggregateType): AggregateManifest? =
        contexts.firstOrNull { it.name == type.context }?.aggregates?.firstOrNull { it.name == type.name }
}

private const val REF = "\$ref"

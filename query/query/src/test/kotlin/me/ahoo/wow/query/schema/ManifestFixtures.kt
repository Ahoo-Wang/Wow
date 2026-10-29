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
import me.ahoo.wow.api.query.annotation.MaskStrategy
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.SnapshotManifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.memory.InMemoryQueryStorageAdapter
import me.ahoo.wow.spi.json.WowJson

/**
 * The Manifest of a `shop.order` aggregate whose state is the `manifest-tools` snapshot of every query keyword
 * (`compiler/manifest-tools/src/test/resources/query-keywords/order-state.defs.json`, #4332), copied byte for byte
 * to `manifest/order-state.defs.json`. Every fixture of `manifest/` is such a copy, under its snapshot's name, and
 * [ManifestFixturesTest] holds it to the snapshot.
 */
internal object ManifestFixtures {
    val ORDER = AggregateType("shop", "order")
    const val ORDER_STATE = "me.ahoo.wow.manifest.tools.QueryKeywordsTest\$OrderState"
    const val REDACT = "me.ahoo.wow.manifest.tools.QueryKeywordsTest\$Redact"

    /** The custom strategy the fixture's `email` names. */
    val REDACT_STRATEGY = MaskStrategy { "[redacted]" }
    val STRATEGIES: MaskStrategyResolver = MaskStrategyResolver.of(mapOf(REDACT to REDACT_STRATEGY))

    @Suppress("UNCHECKED_CAST")
    val ORDER_DEFS: Map<String, Any?> by lazy {
        val text = checkNotNull(javaClass.classLoader.getResource("manifest/order-state.defs.json")).readText()
        WowJson.mapper.readValue(text, Map::class.java) as Map<String, Any?>
    }

    /**
     * The `$defs` `manifest-tools` writes for each state of #5166's item (`QueryKeywordsTest.Item` in a list, in a map,
     * in both and in two lists), keyed by the state's `$defs` key: the snapshot
     * `compiler/manifest-tools/src/test/resources/query-keywords/item-states.defs.json`, copied byte for byte to
     * `manifest/item-states.defs.json`.
     */
    @Suppress("UNCHECKED_CAST")
    val ITEM_STATE_DEFS: Map<String, Map<String, Any?>> by lazy {
        val text = checkNotNull(javaClass.classLoader.getResource("manifest/item-states.defs.json")).readText()
        WowJson.mapper.readValue(text, Map::class.java) as Map<String, Map<String, Any?>>
    }

    /** The `$defs` of `QueryKeywordsTest.SemanticMapsState`, semantic types declared on maps: `manifest/semantic-maps.defs.json`. */
    @Suppress("UNCHECKED_CAST")
    val SEMANTIC_MAPS_DEFS: Map<String, Any?> by lazy {
        val text = checkNotNull(javaClass.classLoader.getResource("manifest/semantic-maps.defs.json")).readText()
        WowJson.mapper.readValue(text, Map::class.java) as Map<String, Any?>
    }
    const val SEMANTIC_MAPS_STATE = "me.ahoo.wow.manifest.tools.QueryKeywordsTest\$SemanticMapsState"

    /** A Manifest of one aggregate `shop.order` with the state [state] (a `$defs` key) among [defs]. */
    fun manifest(
        defs: Map<String, Any?> = ORDER_DEFS,
        state: String = ORDER_STATE,
        snapshots: Boolean = true,
    ): Manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                name = ORDER.context,
                aggregates = listOf(
                    AggregateManifest(
                        name = ORDER.name,
                        state = StateManifest(Manifest.schemaRef(state)),
                        snapshot = SnapshotManifest(snapshots),
                    ),
                ),
            ),
        ),
        defs = defs,
    )

    /** A Manifest whose state `State` is the object of [properties], with [extra] definitions beside it. */
    fun stateManifest(properties: Map<String, Any?>, extra: Map<String, Any?> = emptyMap()): Manifest =
        manifest(defs = extra + ("State" to mapOf("type" to "object", "properties" to properties)), state = "State")

    /** The snapshot schema of `shop.order` compiled from [source] over the in-memory storage. */
    suspend fun schemaOf(
        source: QuerySchemaSource,
        sensitivity: QuerySensitivityPolicy = QuerySensitivityPolicy.DEFAULT,
    ): QueryModelSchema = DefaultQueryModelSchemaProvider(
        QuerySchemaContext(ORDER, QueryModel.SNAPSHOT),
        listOf(source),
        InMemoryQueryStorageAdapter,
        sensitivity = sensitivity,
    ).schema()

    suspend fun orderSchema(sensitivity: QuerySensitivityPolicy = QuerySensitivityPolicy.DEFAULT): QueryModelSchema =
        schemaOf(ManifestQuerySchemaSource(manifest(), STRATEGIES), sensitivity)
}

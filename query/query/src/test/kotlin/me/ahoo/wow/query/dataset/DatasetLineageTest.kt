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

package me.ahoo.wow.query.dataset

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.annotation.MaskStrategy
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.query.annotation.SensitivityLevel.CONFIDENTIAL
import me.ahoo.wow.api.query.annotation.SensitivityLevel.DISPLAY
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.backend.QueryBindings
import me.ahoo.wow.query.engine.QueryEngine
import me.ahoo.wow.query.error.QueryDatasetUnavailableException
import me.ahoo.wow.query.memory.InMemoryDatasetStorage
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.MaskStrategyResolver
import me.ahoo.wow.query.schema.QueryMask
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A dataset inherits its source's protection (#6587): the engine masks a column its table's source protects whether
 * the dataset declares it or not, takes the stricter of the two, refuses a declaration looser than its source or a
 * column holding a document with protected values, and serves unavailable a dataset whose source is unknown, until
 * it declares `"lineage": "none"`.
 */
class DatasetLineageTest {
    private val storage = InMemoryDatasetStorage().also {
        val row = WowJson.mapper.createObjectNode().put("id", "o-1").put("tenant_id", "t1").put("phone", PHONE)
        it.put(TABLE, listOf(row))
    }

    private fun dataset(
        name: AggregateType = ORDERS,
        lineage: QueryDatasetLineage = QueryDatasetLineage.SOURCE,
        block: QueryDatasetBuilder.() -> Unit = {},
    ): QueryDataset = QueryDataset.of(name) {
        tenant("tenant_id")
        identity("id")
        storage(InMemoryDatasetStorage.binding(TABLE))
        lineage(lineage)
        listOf("id", "tenant_id", "phone").forEach { field(it) { types(QueryValueType.STRING) } }
        block()
    }

    private fun protect(level: SensitivityLevel, mask: QueryMask = QueryMask.Keep.of(suffix = 4)) =
        storage.protect(TABLE, mapOf("phone" to QueryDatasetSource.Masked.of("state.phone", level, mask)))

    private fun engine(datasets: QueryDatasets, bindings: QueryBindings<QueryDataset> = storage): QueryEngine =
        QueryEngine.Builder(Manifest()).datasets(datasets, bindings).build()

    private suspend fun QueryEngine.phone(name: AggregateType = ORDERS): String =
        checkNotNull(datasets[name]).list(ListQuery(MatchAllFilter, limit = 10), QueryCaller.IN_PROCESS).toList()
            .single()["phone"].stringValue()

    private fun QueryDatasets.ruleOf(name: AggregateType = ORDERS) =
        checkNotNull(this[name]).declaration.fields.getValue(QueryField("phone")).maskRule

    @Test
    fun `a column its source protects should be masked and not compared, though the dataset declares nothing`() =
        runTest {
            protect(CONFIDENTIAL)
            val datasets = QueryDatasets(listOf(dataset()))
            val engine = engine(datasets)
            engine.phone().assert().isEqualTo("*******1234")
            datasets.ruleOf().assert().isEqualTo(INHERITED)
            assertThrows<QuerySchemaValidationException> {
                checkNotNull(engine.datasets[ORDERS])
                    .count(CountQuery(EqualFilter(QueryField("phone"), PHONE)), QueryCaller.IN_PROCESS)
            }
        }

    @Test
    fun `a declaration as strict as its source or stricter should mask by the declaration`() = runTest {
        protect(DISPLAY, QueryMask.Keep.of(prefix = 3, suffix = 4))
        val stricter = dataset { sensitive("phone", CONFIDENTIAL) }
        val datasets = QueryDatasets(listOf(stricter))
        engine(datasets).phone().assert().isEqualTo("***********")
        datasets.ruleOf().assert().isEqualTo(DeclarationValue.Set(MaskRule(CONFIDENTIAL)))
    }

    @Test
    fun `a declaration looser than its source should be refused, naming the source's path`() {
        protect(CONFIDENTIAL)
        val looser = dataset { sensitive("phone", DISPLAY, QueryMask.Keep.of(suffix = 4)) }
        assertThrows<IllegalArgumentException> { engine(QueryDatasets(listOf(looser))) }.message.assert()
            .contains("Dataset [shop.orders] declares [phone] DISPLAY")
            .contains("looser than its source state.phone of the in-memory table orders, CONFIDENTIAL")
        // A mask that keeps more of the value is looser at the same level.
        val keepsMore = dataset { sensitive("phone", CONFIDENTIAL, QueryMask.Keep.of(prefix = 3, suffix = 4)) }
        assertThrows<IllegalArgumentException> { engine(QueryDatasets(listOf(keepsMore))) }.message.assert()
            .contains("keeping 3 first and 4 last characters, looser than its source")
    }

    @Test
    fun `a looser mask is one that keeps more of either end, or a strategy that is not the source's`() {
        fun rule(mask: QueryMask, level: SensitivityLevel = CONFIDENTIAL) = MaskRule(level, mask, STRATEGIES)
        val keep = QueryMask.Keep.of(prefix = 1, suffix = 4)
        val strategy = QueryMask.Strategy.of("redact")
        DatasetLineages.looser(rule(keep), rule(keep)).assert().isFalse()
        DatasetLineages.looser(rule(QueryMask.FULL), rule(keep)).assert().isFalse()
        DatasetLineages.looser(rule(QueryMask.FULL), rule(strategy)).assert().isFalse()
        DatasetLineages.looser(rule(strategy), rule(strategy)).assert().isFalse()
        DatasetLineages.looser(rule(QueryMask.Keep.of(suffix = 4)), rule(keep)).assert().isFalse()
        DatasetLineages.looser(rule(QueryMask.Keep.of(prefix = 2, suffix = 4)), rule(keep)).assert().isTrue()
        DatasetLineages.looser(rule(QueryMask.Keep.of(prefix = 1, suffix = 5)), rule(keep)).assert().isTrue()
        DatasetLineages.looser(rule(strategy), rule(keep)).assert().isTrue()
        DatasetLineages.looser(rule(keep), rule(strategy)).assert().isTrue()
        DatasetLineages.looser(rule(keep, DISPLAY), rule(keep)).assert().isTrue()
        DatasetLineages.looser(rule(keep), rule(keep, DISPLAY)).assert().isFalse()
    }

    @Test
    fun `a custom strategy declared over a source's kept ends should be refused, naming the strategy`() {
        protect(CONFIDENTIAL)
        val strategy = dataset { sensitive("phone", CONFIDENTIAL, QueryMask.Strategy.of("redact"), STRATEGIES) }
        assertThrows<IllegalArgumentException> {
            QueryEngine.Builder(Manifest()).datasets(QueryDatasets(listOf(strategy)), storage)
                .maskStrategies(STRATEGIES).build()
        }.message.assert().contains("CONFIDENTIAL, masked by the strategy [redact], looser than its source")
    }

    @Test
    fun `an x-wow-mask keyword should read as a snapshot read reads it`() {
        QueryMask.ofKeyword(null).assert().isEqualTo(QueryMask.FULL)
        QueryMask.ofKeyword(mapOf("keepSuffix" to 4)).assert().isEqualTo(QueryMask.Keep.of(suffix = 4))
        QueryMask.ofKeyword(mapOf("strategy" to "redact")).assert().isEqualTo(QueryMask.Strategy.of("redact"))
        assertThrows<IllegalArgumentException> { QueryMask.ofKeyword(mapOf("strategy" to "redact", "keepPrefix" to 1)) }
        // The datasets of none never change, nor are they resolved.
        QueryDatasets.NONE.resolveLineage { error("never") }
    }

    @Test
    fun `a source's custom strategy should be resolved by the engine's strategies`() = runTest {
        storage.protect(
            TABLE,
            mapOf("phone" to QueryDatasetSource.Masked.of("state.phone", DISPLAY, QueryMask.Strategy.of("redact"))),
        )
        val datasets = QueryDatasets(listOf(dataset()))
        QueryEngine.Builder(Manifest()).datasets(datasets, storage).maskStrategies(STRATEGIES).build()
            .phone().assert().isEqualTo("[redacted]")
    }

    @Test
    fun `a dataset whose table's source is unknown should be unavailable, until it declares none`() = runTest {
        // Bindings that know no source, as an application's own bindings may.
        val unknowing = QueryBindings<QueryDataset> { storage.binding(it) }
        val none = dataset(AggregateType("shop", "view"), QueryDatasetLineage.NONE)
        val declared = dataset(AggregateType("shop", "catalog"), QueryDatasetLineage.DECLARED)
        val datasets = QueryDatasets(listOf(dataset(), none, declared))
        val engine = engine(datasets, unknowing)
        assertThrows<QueryDatasetUnavailableException> {
            checkNotNull(engine.datasets[ORDERS]).count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS)
        }.reason.assert().isEqualTo(DatasetLineages.UNRESOLVED)
        engine.phone(none.name).assert().isEqualTo(PHONE)
        engine.phone(declared.name).assert().isEqualTo(PHONE)
        // A dataset already unavailable keeps its own reason.
        datasets.put(dataset().withUnavailable("needs revision"))
        datasets[ORDERS]!!.unavailable.assert().isEqualTo("needs revision")
    }

    @Test
    fun `a column that holds a document with protected values in it should be refused`() {
        storage.protect(TABLE, mapOf("state" to QueryDatasetSource.Opaque.of(listOf("state.phone", "state.card"))))
        val document = dataset { field("state") { types(QueryValueType.STRING) } }
        assertThrows<IllegalArgumentException> { engine(QueryDatasets(listOf(document))) }.message.assert()
            .contains("reads [state], a column of the in-memory table orders that holds the protected values")
            .contains("[state.phone, state.card]")
            .contains("_state_flat")
    }

    @Test
    fun `a dataset that joins should be given its source's protection, or refused, as one served at the start`() {
        protect(CONFIDENTIAL)
        val datasets = QueryDatasets()
        engine(datasets)
        datasets.add(dataset())
        datasets.ruleOf().assert().isEqualTo(INHERITED)
        val looser = dataset { sensitive("phone", DISPLAY) }
        assertThrows<IllegalArgumentException> { datasets.put(looser) }
        // Refused, the dataset served before stays.
        datasets.ruleOf().assert().isEqualTo(INHERITED)
        datasets.replace(dataset { sensitive("phone", CONFIDENTIAL) })
        datasets.ruleOf().assert().isEqualTo(DeclarationValue.Set(MaskRule(CONFIDENTIAL)))
        datasets.remove(ORDERS).assert().isTrue()
        datasets.all.assert().isEmpty()
    }

    @Test
    fun `each engine should resolve the datasets as they are declared, not as another engine served them`() {
        val datasets = QueryDatasets(listOf(dataset()))
        engine(datasets, QueryBindings { storage.binding(it) })
        datasets[ORDERS]!!.unavailable.assert().isEqualTo(DatasetLineages.UNRESOLVED)
        protect(CONFIDENTIAL)
        engine(datasets)
        datasets[ORDERS]!!.unavailable.assert().isNull()
        datasets.ruleOf().assert().isEqualTo(INHERITED)
    }

    @Test
    fun `an in-memory table should be known, holding no protected value unless said, or as the sources given say`() {
        storage.sourceOf(dataset()).assert().isEqualTo(QueryDatasetSource.of("the in-memory table orders"))
        storage.sourceOf(dataset { storage(WowJson.mapper.createObjectNode()) }).assert().isNull()
        val source = QueryDatasetSource.of("elsewhere")
        InMemoryDatasetStorage(sources = { source }).sourceOf(dataset()).assert().isSameAs(source)
        InMemoryDatasetStorage(sources = QueryDatasetSources.NONE).sourceOf(dataset()).assert().isNull()
    }

    @Test
    fun `a source should be a value`() {
        val masked = QueryDatasetSource.Masked.of("state.phone", CONFIDENTIAL)
        masked.assert().isEqualTo(QueryDatasetSource.Masked("state.phone", CONFIDENTIAL, QueryMask.FULL))
        masked.hashCode().assert().isEqualTo(QueryDatasetSource.Masked.of("state.phone", CONFIDENTIAL).hashCode())
        masked.toString().assert()
            .isEqualTo("Masked(path=state.phone, level=CONFIDENTIAL, mask=Keep(prefix=0, suffix=0))")
        val opaque = QueryDatasetSource.Opaque.of(listOf("state.phone"))
        opaque.assert().isEqualTo(QueryDatasetSource.Opaque(listOf("state.phone")))
        opaque.hashCode().assert().isEqualTo(listOf("state.phone").hashCode())
        opaque.toString().assert().isEqualTo("Opaque(paths=[state.phone])")
        assertThrows<IllegalArgumentException> { QueryDatasetSource.Opaque.of(emptyList()) }
        val source = QueryDatasetSource.of("t", mapOf("phone" to masked))
        source.assert().isEqualTo(QueryDatasetSource("t", mapOf("phone" to masked)))
        source.hashCode().assert().isEqualTo(QueryDatasetSource.of("t", mapOf("phone" to masked)).hashCode())
        source.toString().assert().contains("QueryDatasetSource(description=t, columns={phone=Masked(")
        source.assert().isNotEqualTo(QueryDatasetSource.of("t"))
        QueryDatasetSources.NONE.sourceOf(dataset()).assert().isNull()
    }

    companion object {
        private val ORDERS = AggregateType("shop", "orders")
        private const val TABLE = "orders"
        private const val PHONE = "13800001234"
        private val INHERITED = DeclarationValue.Set(MaskRule(CONFIDENTIAL, QueryMask.Keep.of(suffix = 4)))
        private val STRATEGIES = MaskStrategyResolver.of(mapOf("redact" to MaskStrategy { "[redacted]" }))
    }
}

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

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.WindowFunction
import me.ahoo.wow.query.spec.MetricSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

/** The storage-independent rules the Catalog applies to every storage's facts, and what a storage declares. */
class QueryStorageFactsTest {
    private fun objects(vararg alternatives: QueryValueSchema) =
        QueryValueSchema(QueryValueKind.UNION, alternatives = alternatives.toList())

    @Test
    fun `compiling drops the capabilities the logical value cannot have`() {
        val bound = boundSchemaFixture(
            objectFixture(
                "name" to scalarFixture(),
                "createdAt" to scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.MILLISECONDS)),
                "tags" to arrayFixture(scalarFixture()),
                "lines" to arrayFixture(objectFixture("sku" to scalarFixture())),
            ),
        )
        val facts = QueryStorageFacts(
            bound.bindings,
            capabilities = setOf(QueryCapability.FULL_TEXT_TERMS),
            fullProjectionAvailable = false,
            approximateMetrics = setOf("PERCENTILE"),
            storage = StorageSupport(arrayEquality = SupportMode.NONE),
            provisional = true,
        )
        val schema = facts.compile(QueryModel.SNAPSHOT, bound.definition)

        fun granted(path: String) = schema.field(QueryField(path))!!.capabilities
        granted("name").assert().contains(QueryCapability.CURSOR_SORT)
        granted("name").assert().doesNotContain(QueryCapability.AGGREGATE_TEMPORAL, QueryCapability.ELEMENT_SCOPE)
        granted("createdAt").assert().contains(QueryCapability.AGGREGATE_TEMPORAL, QueryCapability.CURSOR_SORT)
        granted("tags").assert().doesNotContain(QueryCapability.CURSOR_SORT, QueryCapability.ELEMENT_SCOPE)
        granted("lines").assert().contains(QueryCapability.ELEMENT_SCOPE)
        granted("lines.sku").assert().doesNotContain(QueryCapability.CURSOR_SORT)
        granted("lines.sku").assert().contains(QueryCapability.EXACT_MATCH)

        schema.capabilities.assert().containsExactly(QueryCapability.FULL_TEXT_TERMS)
        schema.fullProjectionAvailable.assert().isFalse()
        schema.approximateMetrics.assert().containsExactly("PERCENTILE")
        schema.storage.arrayEquality.assert().isEqualTo(SupportMode.NONE)
        schema.provisional.assert().isTrue()
        schema.profile.assert().isSameAs(SnapshotQueryModelProfile)
        facts.compile(QueryModel("custom"), bound.definition).profile.assert().isNull()
    }

    @Test
    fun `bindings that need no rule keep their instance and paths without a logical value are rejected`() {
        val definition = LogicalQuerySchema(objectFixture("name" to scalarFixture()))
        val path = QueryField("name").toPathTemplate()
        val bindings = QueryValueBindings(
            mapOf(QueryCapability.EXACT_MATCH to QueryFieldBindingTemplate(path, null)),
            projectionPath = path,
        )
        val schema = QueryStorageFacts(mapOf(path to bindings)).compile(QueryModel.SNAPSHOT, definition)
        schema.bindings.getValue(path).assert().isSameAs(bindings)

        assertThrows<IllegalArgumentException> {
            QueryStorageFacts(mapOf(QueryField("missing").toPathTemplate() to bindings))
                .compile(QueryModel.SNAPSHOT, definition)
        }
    }

    @Test
    fun `an element scope is an array whose non-null items are all objects`() {
        val item = objectFixture("a" to scalarFixture())
        val nullValue = QueryValueSchema(QueryValueKind.NULL)
        arrayFixture(item).isElementScope().assert().isTrue()
        arrayFixture(objects(item, objectFixture("b" to scalarFixture()))).isElementScope().assert().isTrue()
        arrayFixture(objects(item, nullValue)).isElementScope().assert().isTrue()
        objects(arrayFixture(item), nullValue).isElementScope().assert().isTrue()
        arrayFixture(scalarFixture()).isElementScope().assert().isFalse()
        arrayFixture(objects(item, scalarFixture())).isElementScope().assert().isFalse()
        objects(arrayFixture(item), item).isElementScope().assert().isFalse()
        item.isElementScope().assert().isFalse()
        nullValue.isElementScope().assert().isFalse()
    }

    @Test
    fun `an unavailable storage fails every load with its message`() {
        runTest {
            val storage = UnavailableQueryStorageAdapter("No index.")
            storage.message.assert().isEqualTo("No index.")
            val definition = LogicalQuerySchema(objectFixture())
            assertThrows<QuerySchemaUnavailableException> { storage.facts(definition) }.message.assert()
                .isEqualTo("No index.")
            assertThrows<QuerySchemaUnavailableException> { storage.refresh(definition) }
        }
    }

    @Test
    fun `storage support defaults to native and rejects residual forms that do not exist`() {
        StorageSupport.NATIVE.assert().isEqualTo(StorageSupport())
        StorageSupport.NATIVE.paging.keyset.assert().isEqualTo(SupportMode.NATIVE)
        StorageSupport.NATIVE.paging.maxOffsetWindow.assert().isNull()
        StorageSupport.NATIVE.absentValues.assert().isEqualTo(AbsentValues.DISTINCT)
        PagingSupport(maxOffsetWindow = 10_000).maxOffsetWindow.assert().isEqualTo(10_000)
        listOf(
            { PagingSupport(keyset = SupportMode.RESIDUAL) },
            { PagingSupport(unboundedStream = SupportMode.RESIDUAL) },
            { PagingSupport(maxOffsetWindow = 0) },
            { AggregationSupport(rootGroups = SupportMode.RESIDUAL) },
            { AggregationSupport(percentile = SupportMode.RESIDUAL) },
            { AggregationSupport(distinctCount = SupportMode.RESIDUAL) },
            { AggregationSupport(firstLast = SupportMode.RESIDUAL) },
            { StorageSupport(parallelArraySort = SupportMode.RESIDUAL) },
            { StorageSupport(arrayEquality = SupportMode.RESIDUAL) },
        ).forEach { build -> assertThrows<IllegalArgumentException> { build() } }
        AggregationSupport(having = SupportMode.RESIDUAL, topN = SupportMode.RESIDUAL, denseFill = SupportMode.RESIDUAL)
            .having.assert().isEqualTo(SupportMode.RESIDUAL)
    }

    @Test
    fun `every window is residual by default and a window left out is unsupported`() {
        WindowFunction.entries.forEach {
            AggregationSupport().window(it).assert().isEqualTo(SupportMode.RESIDUAL)
        }
        AggregationSupport.RESIDUAL_WINDOWS.keys.assert().containsExactlyInAnyOrderElementsOf(WindowFunction.entries)
        val first = WindowFunction.entries.first()
        AggregationSupport(windows = mapOf(first to SupportMode.NATIVE)).window(first)
            .assert().isEqualTo(SupportMode.NATIVE)
        WindowFunction.entries.drop(1).forEach {
            AggregationSupport(windows = mapOf(first to SupportMode.NATIVE)).window(it)
                .assert().isEqualTo(SupportMode.NONE)
        }
        AggregationSupport(termsTopN = setOf(MetricSpec.COUNT)).termsTopN.assert().containsExactly(MetricSpec.COUNT)
    }

    @Test
    fun `a storage offers every metric but those it declares unsupported`() {
        MetricSpec.entries.forEach { StorageSupport.NATIVE.offers(it).assert().describedAs(it.name).isTrue() }
        val none = StorageSupport(
            aggregation = AggregationSupport(
                percentile = SupportMode.NONE,
                distinctCount = SupportMode.NONE,
                firstLast = SupportMode.NONE,
            ),
        )
        MetricSpec.entries.filterNot(none::offers).assert().containsExactlyInAnyOrder(
            MetricSpec.PERCENTILE,
            MetricSpec.DISTINCT_COUNT,
            MetricSpec.FIRST,
            MetricSpec.LAST,
        )
    }

    @Test
    fun `the family rules default to the strict table`() {
        QueryStorageFamilyRules.STRICT.assert().isEqualTo(QueryStorageFamilyRules())
        QueryStorageFamilyRules.STRICT.dateOperands.assert().isTrue()
        QueryStorageFamilyRules.STRICT.strictValueTypes.assert().isTrue()
    }
}

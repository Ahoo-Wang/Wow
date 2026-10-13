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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.spec.GroupSpec
import me.ahoo.wow.query.spec.MetricSpec
import me.ahoo.wow.query.spec.OperatorCost
import me.ahoo.wow.query.spec.ValueRule
import me.ahoo.wow.query.spec.spec
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * The compiled capability record of a field: what its value, the capabilities storage grants it, its element scopes
 * and the storage's support let a query do with it, as admission reads it.
 */
class QueryFieldCapabilitiesTest {
    private val root = objectFixture(
        "name" to scalarFixture(),
        "count" to scalarFixture(QueryValueType.INTEGER),
        "createdAt" to scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.MILLISECONDS)),
        "placedOn" to scalarFixture(temporal = Temporal.Formatted("yyyy-MM-dd")),
        "tags" to arrayFixture(scalarFixture()),
        "address" to objectFixture("city" to scalarFixture()),
        "items" to arrayFixture(objectFixture("sku" to scalarFixture())),
        "nickname" to QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(scalarFixture(), QueryValueSchema(QueryValueKind.NULL)),
        ),
        "mixed" to QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(scalarFixture(), arrayFixture(scalarFixture())),
        ),
        "opaque" to QueryValueSchema(QueryValueKind.UNKNOWN),
    )
    private val schema = compile(boundSchemaFixture(root))

    private fun compile(bound: QueryModelSchema, storage: StorageSupport = StorageSupport.NATIVE) =
        QueryStorageFacts(bound.bindings, storage = storage).compile(bound.model, bound.definition)

    private fun effective(path: String, schema: QueryModelSchema = this.schema) =
        schema.field(QueryField(path))!!.effective

    @Test
    fun `a single string is comparable, sortable, cursor-sortable and aggregatable by terms`() {
        val name = effective("name")
        name.granted.assert().contains(QueryCapability.EXACT_MATCH, QueryCapability.CURSOR_SORT)
        name.cardinality.assert().isEqualTo(QueryCardinality.SINGLE)
        name.singleString.assert().isTrue()
        name.scalarDomain.assert().isTrue()
        name.collection.assert().isFalse()
        name.arrayValued.assert().isFalse()
        name.temporal.assert().isNull()
        name.instant.assert().isFalse()
        name.comparable.assert().isTrue()
        name.protected.assert().isFalse()
        name.sortable.assert().isTrue()
        name.cursorSortable.assert().isTrue()
        name.aggregatable.assert().isTrue()
        name.missingKey.assert().isTrue()
        name.inMetricFilter.assert().isTrue()
        name.expressionInput.assert().isTrue()
        name.operators.assert().contains(FilterOperator.EQ, FilterOperator.IS_EMPTY_STRING, FilterOperator.CONTAINS)
        name.operators.assert().doesNotContain(
            FilterOperator.CONTAINS_ALL,
            FilterOperator.IS_EMPTY,
            FilterOperator.TODAY,
            FilterOperator.ELEMENT_MATCH,
            FilterOperator.ID,
            FilterOperator.SEARCH,
        )
        // Without a temporal encoding a field is grouped by terms and number only.
        name.groups.assert().containsExactly(GroupSpec.TERMS, GroupSpec.HISTOGRAM)
        name.metrics.assert().containsExactlyElementsOf(MetricSpec.entries.filter { it.fieldCapabilities.isNotEmpty() })
    }

    @Test
    fun `an epoch is temporal and groups by date, a formatted date is temporal but no instant`() {
        val createdAt = effective("createdAt")
        createdAt.temporal.assert().isEqualTo(Temporal.Epoch(TimeUnit.MILLISECONDS))
        createdAt.instant.assert().isTrue()
        createdAt.operators.assert().contains(FilterOperator.TODAY, FilterOperator.BETWEEN)
        createdAt.groups.assert().containsExactlyElementsOf(GroupSpec.entries)
        createdAt.missingKey.assert().isFalse()

        val placedOn = effective("placedOn")
        placedOn.temporal.assert().isEqualTo(Temporal.Formatted("yyyy-MM-dd"))
        placedOn.instant.assert().isFalse()
        placedOn.operators.assert().contains(FilterOperator.TODAY)
        // The compiled schema drops temporal aggregation from a value that holds no instant.
        placedOn.granted.assert().doesNotContain(QueryCapability.AGGREGATE_TEMPORAL)
        placedOn.groups.assert().doesNotContain(GroupSpec.DATE_HISTOGRAM, GroupSpec.DATE_PART)
    }

    @Test
    fun `a collection matches its elements but never orders a cursor or reads single values`() {
        val tags = effective("tags")
        tags.cardinality.assert().isEqualTo(QueryCardinality.MANY)
        tags.collection.assert().isTrue()
        tags.arrayValued.assert().isTrue()
        tags.singleString.assert().isFalse()
        tags.inMetricFilter.assert().isFalse()
        tags.cursorSortable.assert().isFalse()
        tags.granted.assert().doesNotContain(QueryCapability.CURSOR_SORT, QueryCapability.ELEMENT_SCOPE)
        tags.operators.assert().contains(FilterOperator.CONTAINS_ALL, FilterOperator.IS_EMPTY, FilterOperator.EQ)
        tags.operators.assert().doesNotContain(FilterOperator.IS_EMPTY_STRING)
        tags.metrics.assert().doesNotContain(MetricSpec.ANY, MetricSpec.FIRST, MetricSpec.LAST)
        tags.metrics.assert().contains(MetricSpec.NUMERIC, MetricSpec.DISTINCT_COUNT)
    }

    @Test
    fun `an element scope is an array of objects whose fields lie in it`() {
        val items = effective("items")
        items.granted.assert().contains(QueryCapability.ELEMENT_SCOPE)
        items.scalarDomain.assert().isFalse()
        // ELEMENT_MATCH is the element's own operator, never one of the field operators listed.
        items.operators.assert().doesNotContain(FilterOperator.ELEMENT_MATCH, FilterOperator.EQ)
        items.satisfies(ValueRule.ELEMENT_SCOPE).assert().isTrue()

        val sku = effective("items.sku")
        sku.cursorSortable.assert().isFalse()
        sku.cardinality.assert().isEqualTo(QueryCardinality.SINGLE)
        sku.operators.assert().contains(FilterOperator.EQ)

        val ungranted = compile(
            boundSchemaFixture(
                root,
                fieldCapabilities = setOf(QueryCapability.EXACT_MATCH, QueryCapability.PRESENCE),
            ),
        )
        // An element that grants no scope cannot be entered, so its fields list no operators.
        effective("items.sku", ungranted).operators.assert().isEmpty()
        effective("name", ungranted).operators.assert().contains(FilterOperator.EQ, FilterOperator.EXISTS)
        effective("name", ungranted).aggregatable.assert().isFalse()
        effective("name", ungranted).groups.assert().isEmpty()
        effective("name", ungranted).metrics.assert().isEmpty()
        effective("name", ungranted).sortable.assert().isFalse()
    }

    @Test
    fun `an object has no scalar domain and an optional string is still a single string`() {
        val address = effective("address")
        address.scalarDomain.assert().isFalse()
        address.operators.assert().doesNotContain(FilterOperator.EQ, FilterOperator.GT)
        address.operators.assert().contains(FilterOperator.EXISTS)

        val nickname = effective("nickname")
        nickname.singleString.assert().isTrue()
        nickname.missingKey.assert().isTrue()

        val mixed = effective("mixed")
        mixed.cardinality.assert().isNull()
        mixed.collection.assert().isFalse()
        mixed.arrayValued.assert().isTrue()
        mixed.granted.assert().doesNotContain(QueryCapability.CURSOR_SORT)

        val opaque = effective("opaque")
        opaque.cardinality.assert().isNull()
        opaque.scalarDomain.assert().isFalse()
    }

    @Test
    fun `a value rule is satisfied by the field facts it names`() {
        val name = effective("name")
        val tags = effective("tags")
        val createdAt = effective("createdAt")
        ValueRule.entries.associateWith { rule -> listOf(name, tags, createdAt).map { it.satisfies(rule) } }.assert()
            .isEqualTo(
                mapOf(
                    ValueRule.NONE to listOf(true, true, true),
                    ValueRule.DOMAIN to listOf(true, true, true),
                    ValueRule.COLLECTION_DOMAIN to listOf(false, true, false),
                    ValueRule.COLLECTION to listOf(false, true, false),
                    ValueRule.SINGLE_STRING to listOf(true, false, false),
                    ValueRule.TEMPORAL to listOf(false, false, true),
                    ValueRule.ELEMENT_SCOPE to listOf(true, true, true),
                ),
            )
    }

    @Test
    fun `an entry that refuses expensive operations lists only the normal ones`() {
        val createdAt = effective("createdAt")
        createdAt.operators(allowExpensive = true).assert().isEqualTo(createdAt.operators)
        createdAt.operators(allowExpensive = false).assert()
            .isEqualTo(createdAt.operators.filter { it.spec.baseCost == OperatorCost.NORMAL })
        createdAt.operators(allowExpensive = false).assert().doesNotContain(FilterOperator.NE, FilterOperator.CONTAINS)
        createdAt.groups(allowExpensive = true).assert().isEqualTo(createdAt.groups)
        createdAt.groups(allowExpensive = false).assert().doesNotContain(GroupSpec.DATE_PART)
        createdAt.metrics(allowExpensive = true).assert().isEqualTo(createdAt.metrics)
        createdAt.metrics(allowExpensive = false).assert().doesNotContain(MetricSpec.FIRST, MetricSpec.LAST)
        createdAt.metrics(allowExpensive = false).assert().contains(MetricSpec.NUMERIC)
    }

    @Test
    fun `metrics the storage cannot compute are never listed`() {
        val restricted = compile(
            boundSchemaFixture(root),
            StorageSupport(
                aggregation = AggregationSupport(
                    percentile = SupportMode.NONE,
                    distinctCount = SupportMode.NONE,
                    firstLast = SupportMode.NONE,
                ),
            ),
        )
        effective("count", restricted).metrics.assert().containsExactly(MetricSpec.NUMERIC, MetricSpec.ANY)
        effective("count").metrics.assert().contains(MetricSpec.PERCENTILE, MetricSpec.DISTINCT_COUNT)
    }

    @Test
    fun `a dynamic key compiles the record of its map value`() {
        val tags = compile(boundSchemaFixture(objectFixture("labels" to mapFixture(scalarFixture()))))
        val label = effective("labels.color", tags)
        label.singleString.assert().isTrue()
        label.cursorSortable.assert().isTrue()
        label.operators.assert().contains(FilterOperator.EQ)
        val field = tags.field(QueryField("labels.color"))!!
        field.capabilitiesFor(scalarFixture(QueryValueType.INTEGER)).singleString.assert().isFalse()
    }
}

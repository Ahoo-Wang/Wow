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
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.query.descriptor.ConstraintDescriptor
import me.ahoo.wow.api.query.descriptor.PagingMode
import me.ahoo.wow.api.query.descriptor.SensitivityDescriptor
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class QueryModelDescriptionTest {
    private val schema = boundSchemaFixture(
        objectFixture(
            "aggregateId" to scalarFixture(),
            "tenantId" to scalarFixture(),
            "deleted" to scalarFixture(QueryValueType.BOOLEAN),
            "state" to objectFixture(
                "name" to scalarFixture(),
                "amount" to scalarFixture(QueryValueType.DECIMAL),
                "createdAt" to scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.MILLISECONDS)),
                "tags" to arrayFixture(scalarFixture()),
                "items" to arrayFixture(objectFixture("sku" to scalarFixture())),
                "attributes" to QueryValueSchema(
                    QueryValueKind.OBJECT,
                    properties = mapOf("color" to scalarFixture()),
                    additionalProperties = scalarFixture(),
                ),
                "labels" to QueryValueSchema(QueryValueKind.OBJECT, additionalProperties = arrayFixture(scalarFixture())),
            ),
        ),
    )

    @Test
    fun `fields are listed flat by logical path with what each can do`() {
        val descriptor = schema.describe(QueryBudget.HTTP_DEFAULT, defaultListSize = 100, timeZone = ZoneId.of("UTC"))
        val fields = descriptor.fields.associateBy { it.path }
        descriptor.fields.map { it.path }.assert().isEqualTo(descriptor.fields.map { it.path }.sorted())

        val name = fields.getValue("state.name")
        name.filter.operators.assert().contains(FilterOperator.EQ, FilterOperator.IN, FilterOperator.STARTS_WITH)
            .doesNotContain(FilterOperator.CONTAINS_ALL, FilterOperator.TODAY, FilterOperator.BEFORE_NOW)
        name.sort.paged.assert().isTrue()
        name.aggregate!!.groups.assert().contains("TERMS")
        name.aggregate!!.missingKey.assert().isTrue()

        fields.getValue(
            "state.createdAt"
        ).filter.operators.assert().contains(FilterOperator.TODAY, FilterOperator.BEFORE_NOW)
        fields.getValue("state.createdAt").aggregate!!.groups.assert().contains("DATE_HISTOGRAM", "DATE_PART")
        fields.getValue(
            "state.tags"
        ).filter.operators.assert().contains(FilterOperator.CONTAINS_ALL, FilterOperator.IS_EMPTY)
        fields.getValue("state.tags").aggregate!!.inMetricFilter.assert().isFalse()
        fields.getValue("state.items.sku").scope.assert().isEqualTo("state.items")
        fields.getValue("tenantId").role.assert().isEqualTo("TENANT_ID")
        descriptor.elements.map { it.path }.assert().contains("state.items")
        val attributes = descriptor.dynamic.single { it.pattern == "state.attributes.{key}" }
        // A named property is its own field, so the key pattern does not match it.
        attributes.excludedKeys.assert().containsExactly("color")
        fields.keys.assert().contains("state.attributes.color")
        // An array-valued pattern is one entry, as admission resolves it: collection operators on the array.
        val labels = descriptor.dynamic.filter { it.pattern == "state.labels.{key}" }
        labels.assert().hasSize(1)
        labels.single().kind.assert().isEqualTo(QueryValueKind.ARRAY)
        labels.single().filter.operators.assert().contains(FilterOperator.CONTAINS_ALL, FilterOperator.EQ)
        descriptor.dynamic.map { it.pattern }.assert().doesNotHaveDuplicates()
        descriptor.timeZone.assert().isEqualTo("UTC")
    }

    @Test
    fun `analysis names the approximate metrics and the date histogram units`() {
        schema.describe(null, null).analysis.approximate.assert().isEmpty()
        val estimated = QueryModelSchema(
            schema.model,
            schema.capabilities,
            schema.definition,
            schema.bindings,
            approximateMetrics = setOf("PERCENTILE"),
        )
        val analysis = estimated.describe(null, null).analysis
        analysis.approximate.assert().containsExactly("PERCENTILE")
        analysis.dateUnits.assert().isEqualTo(me.ahoo.wow.api.query.AggregationDateUnit.entries)
        analysis.dateParts.assert().isEqualTo(me.ahoo.wow.api.query.AggregationDatePart.entries)
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            QueryModelSchema(
                schema.model,
                schema.capabilities,
                schema.definition,
                schema.bindings,
                approximateMetrics = setOf("COUNT")
            )
        }
    }

    @Test
    fun `event stream variants list each event's fields relative to the body element`() {
        fun variant(name: String, vararg properties: Pair<String, QueryValueSchema>) = QueryValueSchema(
            QueryValueKind.OBJECT,
            description = "$name event",
            properties = properties.toMap(),
            variant = name,
        )
        val events = boundSchemaFixture(
            objectFixture(
                "id" to scalarFixture(),
                "body" to arrayFixture(
                    objectFixture(
                        "bodyType" to scalarFixture(),
                        "body" to QueryValueSchema(
                            QueryValueKind.UNION,
                            alternatives = listOf(
                                variant("Paid", "amount" to scalarFixture(QueryValueType.DECIMAL)),
                                variant(
                                    "Shipped",
                                    "amount" to scalarFixture(QueryValueType.INTEGER),
                                    "lines" to arrayFixture(objectFixture("sku" to scalarFixture())),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            model = QueryModel.EVENT_STREAM,
        )

        val variants = events.describe(QueryBudget.HTTP_DEFAULT, 100).variants!!

        variants.element.assert().isEqualTo("body")
        variants.discriminator.assert().isEqualTo("bodyType")
        variants.values.map { it.value }.assert().containsExactly("Paid", "Shipped")
        val paid = variants.values.first()
        paid.description.assert().isEqualTo("Paid event")
        paid.fields.map { it.path }.assert().containsExactly("body.amount")
        paid.fields.single().types.assert().containsExactly(QueryValueType.DECIMAL)
        paid.fields.single().scope.assert().isNull()
        val shipped = variants.values.last().fields.associateBy { it.path }
        shipped.keys.assert().containsExactly("body.amount", "body.lines", "body.lines.sku")
        shipped.getValue("body.amount").types.assert().containsExactly(QueryValueType.INTEGER)
        shipped.getValue("body.lines.sku").scope.assert().isEqualTo("body.lines")
        shipped.getValue("body.amount").filter.operators.assert().contains(FilterOperator.EQ, FilterOperator.GT)
        schema.describe(QueryBudget.HTTP_DEFAULT, 100).variants.assert().isNull()
    }

    @Test
    fun `declared enum values carry their descriptions`() {
        val paid = tools.jackson.databind.node.JsonNodeFactory.instance.stringNode("PAID")
        val shipped = tools.jackson.databind.node.JsonNodeFactory.instance.stringNode("SHIPPED")
        val status = QueryValueSchema(
            QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.STRING),
            enumValues = listOf(paid, shipped),
            enumDescriptions = mapOf(paid to "Paid"),
        )
        val descriptor = boundSchemaFixture(objectFixture("state" to objectFixture("status" to status)))
            .describe(QueryBudget.HTTP_DEFAULT, 100)

        descriptor.fields.single { it.path == "state.status" }.enum.assert().containsExactly(
            me.ahoo.wow.api.query.descriptor.EnumValueDescriptor(paid, "Paid"),
            me.ahoo.wow.api.query.descriptor.EnumValueDescriptor(shipped),
        )
    }

    @Test
    fun `the record, limits and constraints are those of the entry`() {
        val descriptor = schema.describe(QueryBudget.HTTP_DEFAULT, defaultListSize = 100)
        descriptor.model.assert().isEqualTo(QueryModel.SNAPSHOT)
        descriptor.record.identity.assert().isEqualTo("aggregateId")
        descriptor.record.paging.assert().containsExactly(PagingMode.LIST, PagingMode.PAGED, PagingMode.CURSOR)
        descriptor.record.defaultScope.assert().isEqualTo(DeletionState.ACTIVE)
        descriptor.record.rootOperators.assert().contains(
            FilterOperator.ID,
            FilterOperator.TENANT_ID,
            FilterOperator.DELETION
        )
        descriptor.limits.maxListSize.assert().isEqualTo(1000)
        descriptor.limits.defaultListSize.assert().isEqualTo(100)
        descriptor.limits.aggregation.maxLimit.assert().isEqualTo(1000)
        descriptor.constraints.assert().contains(
            ConstraintDescriptor(ConstraintDescriptor.CURSOR_UNIQUE_SORT, appended = "aggregateId"),
        )

        val unbudgeted = schema.describe(budget = null, defaultListSize = null)
        unbudgeted.limits.maxListSize.assert().isNull()
        unbudgeted.limits.aggregation.maxLimit.assert().isEqualTo(10_000)
    }

    @Test
    fun `a gated entry lists no expensive operators and states the gates as constraints`() {
        val strict = schema.describe(QueryBudget(QueryBudget.HTTP_LABEL, allowExpensiveOperators = false), 100)
        val name = strict.fields.single { it.path == "state.name" }
        name.filter.operators.assert().doesNotContain(
            FilterOperator.NE,
            FilterOperator.CONTAINS,
            FilterOperator.IS_NULL
        )
            .contains(FilterOperator.EQ, FilterOperator.STARTS_WITH)
        strict.analysis.expressions.assert().isFalse()
        strict.analysis.sort.metrics.assert().isFalse()
        strict.elements.single { it.path == "state.items" }.aggregate.assert().isFalse()
        strict.constraints.map { it.type }.assert().contains(
            ConstraintDescriptor.COUNT_REQUIRES_FILTER,
            ConstraintDescriptor.STARTS_WITH_REQUIRES_PREFIX,
        )
    }

    @Test
    fun `the version is a content hash`() {
        val first = schema.describe(QueryBudget.HTTP_DEFAULT, 100)
        first.version.assert().startsWith("sha256:")
        schema.describe(QueryBudget.HTTP_DEFAULT, 100).version.assert().isEqualTo(first.version)
        schema.describe(QueryBudget(QueryBudget.HTTP_LABEL, maxListSize = 500), 100).version
            .assert().isNotEqualTo(first.version)
    }

    @Test
    fun `protected fields are described without values, aggregation or cursors and no physical facts leak`() {
        val rule = MaskRule(SensitivityLevel.DISPLAY)
        val masked = QueryValueSchema(
            QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.STRING),
            enumValues = listOf(tools.jackson.databind.node.JsonNodeFactory.instance.stringNode("A")),
            maskRule = rule,
        )
        val protected = boundSchemaFixture(objectFixture("state" to objectFixture("secret" to masked)))
        val descriptor = protected.describe(QueryBudget.HTTP_DEFAULT, 100)
        val secret = descriptor.fields.single { it.path == "state.secret" }
        secret.sensitivity.assert().isEqualTo(SensitivityDescriptor(SensitivityLevel.DISPLAY, comparable = true))
        secret.filter.operators.assert().contains(FilterOperator.EQ, FilterOperator.GT)
        secret.sort.paged.assert().isTrue()
        secret.enum.assert().isNull()
        secret.aggregate.assert().isNull()
        secret.sort.cursor.assert().isFalse()
        JsonSerializer.writeValueAsString(descriptor).assert()
            .doesNotContain("native", "FullMaskStrategy", "maskRule", "physical")
        QueryCapability.entries.forEach {
            JsonSerializer.writeValueAsString(descriptor).assert().doesNotContain("\"${it.name}\"")
        }
    }

    @Test
    fun `confidential fields and incomparable display fields are described without comparisons`() {
        fun describe(level: SensitivityLevel, sensitivity: QuerySensitivityPolicy) = boundSchemaFixture(
            objectFixture(
                "state" to objectFixture(
                    "secret" to scalarFixture(mask = MaskRule(level)),
                    "name" to scalarFixture(),
                ),
            ),
            capabilities = setOf(QueryCapability.FULL_TEXT_TERMS),
            sensitivity = sensitivity,
        ).describe(QueryBudget.HTTP_DEFAULT, 100)

        listOf(
            describe(SensitivityLevel.CONFIDENTIAL, QuerySensitivityPolicy.DEFAULT) to SensitivityLevel.CONFIDENTIAL,
            describe(SensitivityLevel.DISPLAY, QuerySensitivityPolicy(displayComparable = false)) to
                SensitivityLevel.DISPLAY,
        ).forEach { (descriptor, level) ->
            val secret = descriptor.fields.single { it.path == "state.secret" }
            secret.sensitivity.assert().isEqualTo(SensitivityDescriptor(level, comparable = false))
            secret.filter.operators.assert().isEmpty()
            secret.sort.paged.assert().isFalse()
            secret.sort.cursor.assert().isFalse()
            secret.aggregate.assert().isNull()
            secret.project.assert().isTrue()
            descriptor.record.search!!.modes.assert().isEmpty()
            descriptor.record.search!!.fields.assert().doesNotContain("state.secret").contains("state.name")
        }
        describe(SensitivityLevel.DISPLAY, QuerySensitivityPolicy.DEFAULT).record.search!!.modes.assert()
            .containsExactly(SearchMode.TERMS)
    }
}

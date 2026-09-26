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

import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.api.query.spec.GroupSpec
import me.ahoo.wow.api.query.spec.MetricInput
import me.ahoo.wow.api.query.spec.MetricSpec
import me.ahoo.wow.api.query.spec.OperatorCost
import me.ahoo.wow.api.query.spec.OperatorTarget
import me.ahoo.wow.api.query.spec.ValueRule
import me.ahoo.wow.api.query.spec.spec

/**
 * The effective capabilities of one field, compiled once from its value definition, the capabilities storage grants
 * it, its sensitivity, its element scopes and the model's storage support (design §5.2, P2). Admission checks each
 * reference against this record, the descriptor publishes it, and it lists each capability with the cost class its
 * spec states, which an entry gate that refuses expensive operations also reads. The value rules of the operator,
 * group and metric specs are decided here, once, so admission and the descriptor cannot disagree about them.
 *
 * What depends on one request (a value within the field's domain, a relative-time configuration, a dynamic key) is
 * still checked by admission.
 */
class QueryFieldCapabilities internal constructor(
    value: QueryValueSchema,
    /** The capabilities storage grants the field. */
    val granted: Set<QueryCapability>,
    /** Whether callers may compare the field's raw value (filters and paged sorts). */
    val comparable: Boolean,
    /** Whether a sensitivity level protects the field: it is then never aggregated. */
    val protected: Boolean,
    /** Whether every element the field lies in grants an element scope, so an `ELEMENT_MATCH` can reach it. */
    scopeGranted: Boolean,
    storage: StorageSupport,
) {
    private val domains = value.operationValues().filter { it.kind != QueryValueKind.NULL }

    /** How many values one record holds for the field; `null` when its value is unknown or mixes both. */
    val cardinality: QueryCardinality? = value.cardinality

    /** Whether any alternative of the value is an array, so storage may hold several values per record. */
    val arrayValued: Boolean = value.hasArrayBranch()

    /** Whether the field is a known collection: every non-null alternative is an array. */
    val collection: Boolean = value.alternativesOrSelf().filter { it.kind != QueryValueKind.NULL }
        .let { it.isNotEmpty() && it.all { alternative -> alternative.kind == QueryValueKind.ARRAY } }

    /** Whether the field holds one string per record (or `null`). */
    val singleString: Boolean = cardinality == QueryCardinality.SINGLE &&
        domains.all { it.valueTypes == setOf(QueryValueType.STRING) }

    /** Whether some value of the field is a scalar a comparison value can be checked against. */
    val scalarDomain: Boolean = domains.any { domain -> domain.valueTypes.any { it != QueryValueType.OBJECT } }

    /**
     * How the field stores time: the one temporal semantic type every non-null value shares, or `null` when its values
     * declare none, or different ones. Relative time resolves against it, and backends read it to decode instants.
     */
    val temporal: Temporal? = domains.map { it.semanticType as? Temporal }.distinct().singleOrNull()

    /** Whether date groups and date differences can read the field's instants: a date or an epoch encoding. */
    val instant: Boolean = temporal == Temporal.Date || temporal is Temporal.Epoch

    /** Whether a metric filter may name the field: metric filters test whole values, so no array alternative. */
    val inMetricFilter: Boolean = !arrayValued

    /** Whether the field is an input of arithmetic aggregation expressions. */
    val expressionInput: Boolean = QueryCapability.AGGREGATE_NUMERIC in granted

    /** Whether `TERMS` may name a key for records without a value: a single-valued string field. */
    val missingKey: Boolean = QueryCapability.AGGREGATE_TERMS in granted && singleString

    /** Whether callers may sort pages by the field. */
    val sortable: Boolean = comparable && QueryCapability.SORT in granted

    /** Whether the field's values are aggregated at all: unprotected, with a terms, numeric or temporal capability. */
    val aggregatable: Boolean = !protected && AGGREGATE_CAPABILITIES.any { it in granted }

    /**
     * The field operators a caller may apply, in operator order: those storage grants whose value rule the field
     * satisfies, on a comparable field inside granted element scopes. A dynamic key's record reads the same checks.
     */
    val operators: List<FilterOperator> = if (!comparable || !scopeGranted) {
        emptyList()
    } else {
        FilterOperator.entries.filter { operator ->
            val spec = operator.spec
            spec.target == OperatorTarget.FIELD && spec.valueRule != ValueRule.ELEMENT_SCOPE &&
                spec.baseCapability in granted && satisfies(spec.valueRule)
        }
    }

    /** The group types that may group by the field, in spec order. */
    val groups: List<GroupSpec> = if (!aggregatable) {
        emptyList()
    } else {
        GroupSpec.entries.filter {
            it.capability in granted && (it.capability != QueryCapability.AGGREGATE_TEMPORAL || instant)
        }
    }

    /** The metric types that may read the field, in spec order; `COUNT` and `DERIVED` read none. */
    val metrics: List<MetricSpec> = if (!aggregatable) {
        emptyList()
    } else {
        MetricSpec.entries.filter { metric ->
            metric.input != MetricInput.NONE && metric.fieldCapabilities.any { it in granted } &&
                (metric.input != MetricInput.SINGLE_VALUE_FIELD || cardinality == QueryCardinality.SINGLE) &&
                storage.offers(metric)
        }
    }

    /** Whether the field satisfies [rule], the value rule of a filter operator's spec. */
    fun satisfies(rule: ValueRule): Boolean = when (rule) {
        ValueRule.COLLECTION -> collection
        ValueRule.COLLECTION_DOMAIN -> collection && scalarDomain
        ValueRule.SINGLE_STRING -> singleString
        ValueRule.TEMPORAL -> temporal != null
        // A comparison value must be a scalar of the field's domain: an object-valued field has none to offer.
        ValueRule.DOMAIN -> scalarDomain
        ValueRule.NONE, ValueRule.ELEMENT_SCOPE -> true
    }

    /** [operators], without the expensive ones unless [allowExpensive]. */
    fun operators(allowExpensive: Boolean): List<FilterOperator> =
        if (allowExpensive) operators else operators.filter { it.spec.baseCost != OperatorCost.EXPENSIVE }

    /** [groups], without the expensive ones unless [allowExpensive]. */
    fun groups(allowExpensive: Boolean): List<GroupSpec> =
        if (allowExpensive) groups else groups.filter { it.baseCost != OperatorCost.EXPENSIVE }

    /** [metrics], without the expensive ones unless [allowExpensive]. */
    fun metrics(allowExpensive: Boolean): List<MetricSpec> =
        if (allowExpensive) metrics else metrics.filter { it.baseCost != OperatorCost.EXPENSIVE }

    private companion object {
        val AGGREGATE_CAPABILITIES = listOf(
            QueryCapability.AGGREGATE_TERMS,
            QueryCapability.AGGREGATE_NUMERIC,
            QueryCapability.AGGREGATE_TEMPORAL,
        )
    }
}

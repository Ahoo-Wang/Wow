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

package me.ahoo.wow.api.query.descriptor

import com.fasterxml.jackson.annotation.JsonInclude
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.query.schema.QueryDeprecation
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import tools.jackson.databind.JsonNode

/**
 * How a query model can be queried on one entry: the answer `GET …/schema` gives to agents and the view engine.
 *
 * It publishes conclusions, never storage vocabulary: every listed operator, sort, group and function is admitted when
 * used on its own, anything unlisted is rejected; combination rules are in [constraints]. Values, scopes and policy
 * conditions may still reject a query at run time, with a structured violation. It exposes no physical names and does
 * not depend on the caller, so it is cacheable; [version] is a hash of its content and doubles as the ETag.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class QueryModelDescriptor(
    val model: QueryModel,
    val version: String,
    /** The server's default time zone, used when a request names none. */
    val timeZone: String,
    val record: RecordDescriptor,
    val limits: LimitsDescriptor,
    val analysis: AnalysisDescriptor,
    /** Every queryable field by logical path, fields inside elements included (see [FieldDescriptor.scope]). */
    val fields: List<FieldDescriptor>,
    val elements: List<ElementDescriptor>,
    /** Fields under dynamic map keys, written with `{key}` in place of the key. */
    val dynamic: List<DynamicFieldDescriptor>,
    val constraints: List<ConstraintDescriptor>,
    /** The variants of an element whose records differ by a discriminator, or `null` when the model has none. */
    val variants: VariantsDescriptor? = null,
)

/**
 * An element of every record whose fields differ by variant: an EventStream record's `body` holds events whose
 * payload fields depend on `bodyType`. A condition on one variant's field must be written inside an `ELEMENT_MATCH`
 * on [element] together with the [discriminator], so both apply to the same element.
 */
data class VariantsDescriptor(
    /** The element holding the variants, e.g. `body`. */
    val element: String,
    /** The element-relative field naming each element's variant, e.g. `bodyType`. */
    val discriminator: String,
    val values: List<VariantDescriptor>,
)

/** One variant: its discriminator [value] and the fields it has, by paths relative to the element. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class VariantDescriptor(
    val value: String,
    val fields: List<FieldDescriptor>,
    val description: String? = null,
)

enum class PagingMode { LIST, PAGED, CURSOR }

@JsonInclude(JsonInclude.Include.NON_NULL)
data class RecordDescriptor(
    val identity: String,
    val paging: List<PagingMode>,
    /** The deletion scope applied when a query states none; `null` when the model has none. */
    val defaultScope: DeletionState?,
    /** Operators that take no field: they filter by the model's system fields. */
    val rootOperators: List<FilterOperator>,
    /** Model-wide full-text search, or `null` when the model offers none. */
    val search: SearchDescriptor?,
)

data class SearchDescriptor(val modes: List<SearchMode>, val fields: List<String>)

/** Effective limits of this entry: protocol limits and the entry's budget, whichever is smaller; `null` is unlimited. */
data class LimitsDescriptor(
    val maxListSize: Int?,
    val defaultListSize: Int?,
    val maxPageSize: Int?,
    val maxPageWindow: Long?,
    val maxFilterNodes: Int?,
    val maxFilterValues: Int?,
    val maxSortFields: Int,
    val aggregation: AggregationLimitsDescriptor,
)

data class AggregationLimitsDescriptor(
    val maxGroups: Int,
    val maxMetrics: Int,
    val maxElements: Int,
    val maxLimit: Int,
    val maxExpressionDepth: Int,
    val maxExpressionNodes: Int,
)

data class AnalysisDescriptor(
    val metrics: List<String>,
    /** The [metrics] whose results this backend estimates rather than computes exactly. */
    val approximate: List<String>,
    /** Whether arithmetic expressions may feed metrics. */
    val expressions: Boolean,
    val having: HavingDescriptor,
    val sort: AnalysisSortDescriptor,
    val dense: Boolean,
    /** The calendar units a `DATE_HISTOGRAM` group may bucket by. */
    val dateUnits: List<AggregationDateUnit>,
    /** The calendar parts a `DATE_PART` group may group by. */
    val dateParts: List<AggregationDatePart>,
)

data class HavingDescriptor(val metrics: List<String>)

data class AnalysisSortDescriptor(val groups: Boolean, val metrics: Boolean)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class FieldDescriptor(
    val path: String,
    /** The system role of a system field, e.g. `TENANT_ID`; `null` for ordinary fields. */
    val role: String?,
    val types: Set<QueryValueType>,
    val kind: QueryValueKind,
    val nullable: Boolean,
    val semantic: QuerySemanticType?,
    /** Declared values; never listed for protected fields. */
    val enum: List<EnumValueDescriptor>?,
    val description: String?,
    val sensitivity: SensitivityDescriptor?,
    val project: Boolean,
    val filter: FieldFilterDescriptor,
    val sort: FieldSortDescriptor,
    /** `null` when the field cannot be aggregated at all. */
    val aggregate: FieldAggregateDescriptor?,
    /** The element this field lives in, or `null` at the record level. */
    val scope: String?,
    /** Set when the field is deprecated: still queryable, but new queries should avoid it. */
    val deprecated: QueryDeprecation? = null,
    /** Other paths a query may use for this field; admission replaces them with [path]. */
    val aliases: List<String> = emptyList(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class EnumValueDescriptor(val value: JsonNode, val description: String? = null)

/**
 * A sensitive field's protection: its [level] and whether filters and paged sorts may still compare its raw value.
 * [comparable] is `false` for [SensitivityLevel.CONFIDENTIAL], and for [SensitivityLevel.DISPLAY] when the server
 * turned comparison off.
 */
data class SensitivityDescriptor(val level: SensitivityLevel, val comparable: Boolean)

data class FieldFilterDescriptor(val operators: List<FilterOperator>)

data class FieldSortDescriptor(val paged: Boolean, val cursor: Boolean)

data class FieldAggregateDescriptor(
    val groups: List<String>,
    val missingKey: Boolean,
    val functions: List<String>,
    val distinctCount: Boolean,
    val percentile: Boolean,
    val any: Boolean,
    val expressionInput: Boolean,
    val inMetricFilter: Boolean,
)

data class ElementDescriptor(val path: String, val filter: Boolean, val aggregate: Boolean)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DynamicFieldDescriptor(
    val pattern: String,
    val types: Set<QueryValueType>,
    val kind: QueryValueKind,
    val filter: FieldFilterDescriptor,
    /** Keys that are declared separately and therefore excluded from this pattern. */
    val excludedKeys: List<String>?,
)

/** A combination rule that per-field listings cannot express. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ConstraintDescriptor(
    val type: String,
    val appended: String? = null,
    /** The fields the constraint is about, when it names several. */
    val fields: List<String>? = null,
) {
    companion object {
        /** A cursor's sort always ends with the identity field, appended when absent. */
        const val CURSOR_UNIQUE_SORT = "CURSOR_UNIQUE_SORT"

        /** A count or paged query must not match every record. */
        const val COUNT_REQUIRES_FILTER = "COUNT_REQUIRES_FILTER"

        /** STARTS_WITH needs a non-empty, case-sensitive prefix. */
        const val STARTS_WITH_REQUIRES_PREFIX = "STARTS_WITH_REQUIRES_PREFIX"

        /**
         * A sort names at most one of [fields]: they are array-valued and the storage cannot order by two independent
         * arrays. Fields on one array path (one nested in the other) may still combine.
         */
        const val PARALLEL_ARRAY_SORT = "PARALLEL_ARRAY_SORT"

        /**
         * On [fields], a stored `null` or empty array is indistinguishable from a missing field: `EXISTS` does not
         * match it, `NOT_EXISTS` and `IS_NULL` do, and `IS_EMPTY` also matches a missing or `null` field.
         */
        const val NULL_OR_EMPTY_AS_MISSING = "NULL_OR_EMPTY_AS_MISSING"

        /** `EQ` and `NE` accept only a scalar operand: the storage cannot compare a whole array. */
        const val ARRAY_EQUALITY = "ARRAY_EQUALITY"
    }
}

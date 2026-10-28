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

import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DateDiffUnit
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagingMode
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.WindowFunction
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.paged
import me.ahoo.wow.query.descriptor.AggregationLimitsDescriptor
import me.ahoo.wow.query.descriptor.AnalysisDescriptor
import me.ahoo.wow.query.descriptor.AnalysisSortDescriptor
import me.ahoo.wow.query.descriptor.ConstraintDescriptor
import me.ahoo.wow.query.descriptor.DynamicFieldDescriptor
import me.ahoo.wow.query.descriptor.ElementDescriptor
import me.ahoo.wow.query.descriptor.EnumValueDescriptor
import me.ahoo.wow.query.descriptor.FieldAggregateDescriptor
import me.ahoo.wow.query.descriptor.FieldDescriptor
import me.ahoo.wow.query.descriptor.FieldFilterDescriptor
import me.ahoo.wow.query.descriptor.FieldSortDescriptor
import me.ahoo.wow.query.descriptor.HavingDescriptor
import me.ahoo.wow.query.descriptor.LimitsDescriptor
import me.ahoo.wow.query.descriptor.QueryModelDescriptor
import me.ahoo.wow.query.descriptor.RecordDescriptor
import me.ahoo.wow.query.descriptor.SearchDescriptor
import me.ahoo.wow.query.descriptor.SensitivityDescriptor
import me.ahoo.wow.query.descriptor.StreamDescriptor
import me.ahoo.wow.query.json.QueryJson
import me.ahoo.wow.query.spec.MetricSpec
import me.ahoo.wow.query.spec.OperatorCost
import me.ahoo.wow.query.spec.OperatorTarget
import me.ahoo.wow.query.spec.SystemField
import me.ahoo.wow.query.spec.spec
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.HexFormat

/**
 * Describes how this model can be queried on one entry, derived from the same capability table and operator specs that
 * admission reads, so the description and admission cannot disagree.
 *
 * @param budget the entry's budget; `null` for an unbudgeted entry.
 * @param defaultListSize the list size an entry applies when a list query sends none.
 * @param timeZone the zone a request that names none is read in: UTC.
 */
internal fun QueryModelSchema.describe(
    budget: QueryBudget?,
    defaultListSize: Int?,
    timeZone: ZoneId = ZoneOffset.UTC,
): QueryModelDescriptor = describedOnce(DescriptorKey(budget, defaultListSize, timeZone)) {
    QueryModelDescription(this, budget).descriptor(defaultListSize, timeZone)
}

/**
 * What a descriptor depends on besides its schema. A schema is immutable (a new version is a new instance), so the
 * descriptor and its content hash are computed once per key; [budget] compares by identity, as an entry policy
 * holds one budget per entry.
 */
internal data class DescriptorKey(val budget: QueryBudget?, val defaultListSize: Int?, val timeZone: ZoneId)

private class QueryModelDescription(private val schema: QueryModelSchema, private val budget: QueryBudget?) {
    private val allowExpensive = budget?.allowExpensiveOperators ?: true
    private val identity = schema.profile?.identityField?.path

    /** Whether the storage pages by keyset at all: admission rejects every cursor query where it does not. */
    private val keyset = schema.storage.paging.keyset != SupportMode.NONE

    fun descriptor(defaultListSize: Int?, timeZone: ZoneId): QueryModelDescriptor {
        val paths = schema.definition.values.filterKeys { it.segments.isNotEmpty() }
        // Resolved exactly as admission resolves them: one entry per logical path, array items implicit.
        val fields = paths.keys.filter { it.keyCount == 0 && it.segments.last() != QueryPathSegment.Item }
            .map { it.logicalPath() }.distinct()
            .mapNotNull { path -> schema.field(QueryField(path))?.let(::field) }
        // A cursor orders by the model's unique key last, so every field of it must order a cursor.
        val cursorFields = fields.filter { it.sort.cursor }.mapTo(hashSetOf()) { it.path }
        val cursor = identity != null && schema.cursorUniqueKey().all { it.path in cursorFields }
        val described = QueryModelDescriptor(
            model = schema.model,
            version = "",
            timeZone = timeZone.id,
            record = record(fields, cursor),
            limits = limits(defaultListSize),
            analysis = analysis(),
            fields = fields.sortedBy { it.path },
            elements = fields.filter { it.path in elementPaths }.map {
                ElementDescriptor(it.path, filter = true, aggregate = allowExpensive, search = elementSearch(it.path))
            },
            // Resolved like fields: one entry per logical pattern, array items implicit.
            dynamic = paths.keys.filter { it.keyCount != 0 && it.segments.last() != QueryPathSegment.Item }
                .distinctBy { it.logicalPath() }
                .mapNotNull(::dynamic)
                .sortedBy { it.pattern },
            constraints = constraints(cursor, parallelArraySortFields(fields), absentAsMissingFields(fields)),
        )
        return described.copy(version = versionOf(described))
    }

    private val elementPaths = mutableSetOf<String>()

    /** Describes [field] with its capabilities, or `null` when it has none and cannot be projected. */
    private fun field(field: QueryFieldSchema): FieldDescriptor? {
        val capabilities = field.capabilities
        val projectable = field.projectionField != null
        if (capabilities.isEmpty() && !projectable) return null
        val path = field.logicalField.path
        if (QueryCapability.ELEMENT_SCOPE in capabilities) elementPaths += path
        val value = field.value
        val effective = field.effective
        return FieldDescriptor(
            path = path,
            role = role(path),
            types = value.typesInOrder(),
            kind = value.kind,
            nullable = value.nullable,
            semantic = value.sharedSemantic(),
            enum = value.enumValues?.takeUnless { field.protected }
                ?.map { EnumValueDescriptor(it, value.enumDescriptions[it]) },
            description = value.description,
            sensitivity = (value.maskRule?.level ?: field.protection?.takeUnless { value.kind == QueryValueKind.OBJECT })
                ?.let { SensitivityDescriptor(it, field.comparable) },
            project = projectable,
            filter = FieldFilterDescriptor(effective.operators(allowExpensive)),
            sort = FieldSortDescriptor(
                paged = effective.sortable,
                cursor = effective.cursorSortable && keyset,
                indexed = field.sortIndexed().takeIf { effective.sortable },
            ),
            aggregate = aggregate(effective),
            scope = field.elementAncestors?.lastOrNull()?.path,
            deprecated = schema.definition.deprecations[field.logicalField],
            aliases = aliasesByField[field.logicalField].orEmpty(),
        )
    }

    private val aliasesByField: Map<QueryField, List<String>> by lazy {
        schema.definition.aliases.entries.groupBy({ it.value }, { it.key.path }).mapValues { it.value.sorted() }
    }

    private fun aggregate(effective: QueryFieldCapabilities): FieldAggregateDescriptor? {
        if (!effective.aggregatable) return null
        val metrics = effective.metrics(allowExpensive)
        return FieldAggregateDescriptor(
            groups = effective.groups(allowExpensive).map(WireNames::of),
            missingKey = effective.missingKey,
            functions = if (MetricSpec.NUMERIC in metrics) {
                AggregationFunction.entries.map(
                    WireNames::of
                )
            } else {
                emptyList()
            },
            distinctCount = MetricSpec.DISTINCT_COUNT in metrics,
            percentile = MetricSpec.PERCENTILE in metrics,
            any = MetricSpec.ANY in metrics,
            firstLast = MetricSpec.FIRST in metrics,
            expressionInput = effective.expressionInput,
            inMetricFilter = effective.inMetricFilter,
        )
    }

    /**
     * Describes a dynamic pattern by resolving a probe key through [QueryModelSchema.field], exactly as admission
     * resolves a concrete key, so an array pattern and its items yield one entry. The operators are the probe's
     * compiled record, so the key's sensitivity and element scopes decide them as they decide a concrete key's.
     */
    private fun dynamic(path: QueryPathTemplate): DynamicFieldDescriptor? {
        val excluded = schema.definition.keyExclusions(path).values.flatten().toSet()
        val probe = generateSequence(PROBE_KEY) { "_$it" }.first { it !in excluded }
        val field = schema.field(path.field(List(path.keyCount) { probe })) ?: return null
        if (field.capabilities.isEmpty()) return null
        val value = field.value
        return DynamicFieldDescriptor(
            pattern = path.logicalPath(),
            types = value.typesInOrder(),
            kind = value.kind,
            filter = FieldFilterDescriptor(field.effective.operators(allowExpensive)),
            excludedKeys = schema.definition.keyExclusions(path).values.flatten().distinct().sorted()
                .takeIf { it.isNotEmpty() },
        )
    }

    private fun record(fields: List<FieldDescriptor>, cursor: Boolean): RecordDescriptor {
        val byPath = fields.associateBy { it.path }
        val rootOperators = FilterOperator.entries.filter { operator ->
            val spec = operator.spec
            when (spec.target) {
                OperatorTarget.SYSTEM_FIELD ->
                    systemPath(spec.systemField!!)?.let { byPath[it] }?.filter?.operators?.isNotEmpty() == true
                OperatorTarget.EXPRESSION -> allowExpensive
                else -> false
            }
        }
        // Model-wide search matches every searchable field, so a field that must not be compared rules it out.
        val modes = if (schema.hasIncomparableFields) {
            emptyList()
        } else {
            listOfNotNull(
                SearchMode.TERMS.takeIf { schema.supports(QueryCapability.FULL_TEXT_TERMS) },
                SearchMode.PHRASE.takeIf { schema.supports(QueryCapability.FULL_TEXT_PHRASE) },
            )
        }
        val searchFields = searchable[null].orEmpty().map { it.logicalField.path }
        return RecordDescriptor(
            identity = identity.orEmpty(),
            paging = listOfNotNull(PagingMode.LIST, PagingMode.PAGED, PagingMode.CURSOR.takeIf { cursor }),
            defaultScope = (schema.profile?.defaultScope(MatchAllFilter) as? DeletionFilter)?.deletionState,
            rootOperators = rootOperators,
            search = if (modes.isEmpty() && searchFields.isEmpty()) null else SearchDescriptor(modes, searchFields),
            stream = StreamDescriptor(resumable = cursor),
        )
    }

    /**
     * The fields a `SEARCH` may name, by the element they live in (`null` for the record): granted a full-text
     * capability by storage and comparable. An element field is searchable only inside `ELEMENT_MATCH` on its element,
     * so it is never listed for the record.
     */
    private val searchable: Map<String?, List<QueryFieldSchema>> by lazy {
        schema.bindings.filterValues {
            QueryCapability.FULL_TEXT_TERMS in it.bindings || QueryCapability.FULL_TEXT_PHRASE in it.bindings
        }.keys.filter { it.keyCount == 0 && it.segments.isNotEmpty() }.map { it.logicalPath() }.distinct().sorted()
            .mapNotNull { path -> schema.field(QueryField(path)) }
            .filter { it.comparable && it.elementAncestors != null }
            .groupBy { it.elementAncestors?.lastOrNull()?.path }
    }

    /** Search inside [element]: its searchable fields and the modes all of them accept. */
    private fun elementSearch(element: String): SearchDescriptor? {
        val fields = searchable[element]?.takeIf { it.isNotEmpty() } ?: return null
        val modes = listOfNotNull(
            SearchMode.TERMS.takeIf { fields.all { it.binding(QueryCapability.FULL_TEXT_TERMS) != null } },
            SearchMode.PHRASE.takeIf { fields.all { it.binding(QueryCapability.FULL_TEXT_PHRASE) != null } },
        )
        return SearchDescriptor(modes, fields.map { it.logicalField.path })
    }

    /**
     * The path a system field names on this model ([systemField]); `null` for the identity of a custom model and for a
     * system field the model's profile does not carry, such as a dataset's aggregate id.
     */
    private fun systemPath(field: SystemField): String? = when {
        field == SystemField.IDENTITY -> identity
        schema.profile == null -> schema.systemField(field).path
        else -> schema.profile.systemField(field)?.path
    }

    /** The system role of [path]: a metadata field's own, or the identity's for the model's identity field. */
    private fun role(path: String): String? = METADATA_FIELDS.firstOrNull { systemPath(it) == path }?.let(WireNames::of)
        ?: WireNames.of(SystemField.IDENTITY).takeIf { path == identity }
        ?: FieldDescriptor.EVENT_TIME.takeIf { path == schema.profile?.eventTimeField?.path }
        ?: FieldDescriptor.FIRST_EVENT_TIME.takeIf { path == schema.profile?.firstEventTimeField?.path }

    /**
     * The furthest record an offset page may reach (`index × size`): the budget's window or the storage's own
     * ([me.ahoo.wow.query.schema.PagingSupport.maxOffsetWindow], Elasticsearch's `index.max_result_window`),
     * whichever is smaller, as admission enforces both. A budget of 0 sets no window of its own, so the storage's
     * still applies; `null` only when neither sets one.
     */
    private fun pageWindow(): Long? = listOfNotNull(
        budget?.maxPageWindow?.takeIf { it > 0 },
        schema.storage.paging.maxOffsetWindow?.toLong(),
    ).minOrNull()

    private fun limits(defaultListSize: Int?): LimitsDescriptor {
        fun Int.limit(): Int? = takeIf { it > 0 }
        val maxList = budget?.maxListSize?.limit()
        return LimitsDescriptor(
            maxListSize = maxList,
            defaultListSize = defaultListSize?.let { if (budget == null) it.limit() else budget.listDefault(it) },
            maxPageSize = budget?.maxPageSize?.limit(),
            maxPageWindow = pageWindow(),
            maxFilterNodes = budget?.maxFilterNodes?.limit(),
            maxFilterValues = budget?.maxFilterValues?.limit(),
            maxSortFields = Sort.MAX_FIELDS,
            aggregation = AggregationLimitsDescriptor(
                maxGroups = AggregationQuery.MAX_GROUPS,
                maxMetrics = AggregationQuery.MAX_METRICS,
                maxElements = AggregationQuery.MAX_ELEMENTS,
                maxLimit = maxList?.let { minOf(it, AggregationQuery.MAX_LIMIT) } ?: AggregationQuery.MAX_LIMIT,
                maxExpressionDepth = AggregationQuery.MAX_EXPRESSION_DEPTH,
                maxExpressionNodes = AggregationQuery.MAX_EXPRESSION_NODES,
                maxResidualGroups = budget?.maxResidualGroups?.limit(),
            ),
        )
    }

    private fun analysis(): AnalysisDescriptor {
        // A metric, HAVING, metric sort or dense fill the storage declares NONE is rejected at admission, so it is
        // not listed; neither is an expensive metric on an entry that refuses expensive operations. DERIVED is the
        // one exception: it stays listed, since `expressions` states whether its arithmetic is allowed.
        val support = schema.storage.aggregation
        val specs = MetricSpec.entries.filter { metric ->
            schema.storage.offers(metric) &&
                (allowExpensive || metric.baseCost != OperatorCost.EXPENSIVE || metric == MetricSpec.DERIVED)
        }
        val firstLast = MetricSpec.FIRST in specs
        val having = if (support.having == SupportMode.NONE) emptyList() else specs.filter { it.havingOperand }
        // A storage names its approximate metrics by their specs' names; the descriptor lists every metric by its wire
        // name, the `type` an aggregation query gives it.
        return AnalysisDescriptor(
            metrics = specs.map(WireNames::of),
            approximate = specs.filter { it.name in schema.approximateMetrics }.map(WireNames::of),
            expressions = allowExpensive,
            having = HavingDescriptor(having.map(WireNames::of)),
            sort = AnalysisSortDescriptor(groups = true, metrics = allowExpensive && support.topN != SupportMode.NONE),
            // Dense fill materializes every bucket of the range, an expensive group (GroupSpec.cost).
            dense = allowExpensive && support.denseFill != SupportMode.NONE,
            dateUnits = AggregationDateUnit.entries,
            dateParts = AggregationDatePart.entries,
            dateDiffUnits = if (allowExpensive) DateDiffUnit.entries else emptyList(),
            firstLastOrderBy = schema.profile?.eventTimeField?.path?.takeIf { firstLast },
            // A window is a derived metric, an expensive one.
            windows = WindowFunction.entries.filter { allowExpensive && support.window(it) != SupportMode.NONE },
            // Expanding elements is itself expensive (the budget refuses it otherwise).
            rootGroups = allowExpensive && support.rootGroups != SupportMode.NONE,
        )
    }

    /** The array-valued sort fields, when the storage cannot sort by two independent arrays. */
    private fun parallelArraySortFields(fields: List<FieldDescriptor>): List<String> {
        if (schema.storage.parallelArraySort != SupportMode.NONE) return emptyList()
        return fields.filter { it.sort.paged && schema.field(QueryField(it.path))?.effective?.arrayValued == true }
            .map { it.path }.sorted()
    }

    /** The fields whose presence operators cannot tell a stored `null` or empty array from a missing field. */
    private fun absentAsMissingFields(fields: List<FieldDescriptor>): List<String> {
        if (schema.storage.absentValues != AbsentValues.AS_MISSING) return emptyList()
        return fields.filter { descriptor ->
            val field = schema.field(QueryField(descriptor.path)) ?: return@filter false
            descriptor.filter.operators.any { it in PRESENCE_OPERATORS } &&
                (field.value.nullable || field.effective.arrayValued)
        }.map { it.path }.sorted()
    }

    private fun constraints(
        cursor: Boolean,
        parallelArraySort: List<String>,
        absentAsMissing: List<String>,
    ): List<ConstraintDescriptor> = listOfNotNull(
        ConstraintDescriptor(ConstraintDescriptor.PARALLEL_ARRAY_SORT, fields = parallelArraySort)
            .takeIf { parallelArraySort.size > 1 },
        ConstraintDescriptor(ConstraintDescriptor.NULL_OR_EMPTY_AS_MISSING, fields = absentAsMissing)
            .takeIf { absentAsMissing.isNotEmpty() },
        ConstraintDescriptor(ConstraintDescriptor.ARRAY_EQUALITY)
            .takeIf { schema.storage.arrayEquality == SupportMode.NONE },
        identity?.takeIf { cursor }?.let {
            ConstraintDescriptor(
                ConstraintDescriptor.CURSOR_UNIQUE_SORT,
                appended = it,
                fields = schema.cursorUniqueKey().map(QueryField::path),
            )
        },
        ConstraintDescriptor(ConstraintDescriptor.COUNT_REQUIRES_FILTER).takeUnless { allowExpensive },
        ConstraintDescriptor(ConstraintDescriptor.STARTS_WITH_REQUIRES_PREFIX).takeUnless { allowExpensive },
    )
}

/** The operators whose result depends on whether a stored `null` or empty array counts as present. */
private val PRESENCE_OPERATORS = setOf(
    FilterOperator.EXISTS,
    FilterOperator.NOT_EXISTS,
    FilterOperator.IS_NULL,
    FilterOperator.IS_NOT_NULL,
    FilterOperator.IS_EMPTY,
)

/**
 * What the field's values mean, read like its types through array items and union alternatives: the one semantic
 * type every non-null value shares, or `null` when they declare none or different ones.
 */
private fun QueryValueSchema.sharedSemantic(): QuerySemanticType? =
    operationValues().filter { it.kind != QueryValueKind.NULL }.map { it.semanticType }.distinct().singleOrNull()

private fun QueryValueSchema.typesInOrder(): Set<QueryValueType> =
    operationValues().flatMapTo(sortedSetOf(compareBy { it.value })) { it.valueTypes }

/** The logical path as clients write it: properties joined by `.`, array items implicit, map keys as `{key}`. */
internal fun QueryPathTemplate.logicalPath(): String = segments.mapNotNull {
    when (it) {
        is QueryPathSegment.Property -> it.name
        is QueryPathSegment.Key -> "{key}"
        QueryPathSegment.Item -> null
    }
}.joinToString(".")

/**
 * This descriptor with [extra] appended to its constraints and its version computed again, or itself without any: a
 * rule the gateway adds to its model's, such as [me.ahoo.wow.query.events.UnpinnedEventsPolicy.CONSTRAINT].
 */
internal fun QueryModelDescriptor.withConstraints(extra: List<ConstraintDescriptor>): QueryModelDescriptor {
    if (extra.isEmpty()) return this
    val described = copy(version = "", constraints = constraints + extra)
    return described.copy(version = versionOf(described))
}

/** `sha256:` of the canonical JSON: object members sorted by name, so equal content always has equal versions. */
private fun versionOf(descriptor: QueryModelDescriptor): String {
    val canonical = StringBuilder().also { QueryJson.mapper.valueToTree<JsonNode>(descriptor).canonical(it) }.toString()
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return "sha256:" + HexFormat.of().formatHex(digest)
}

private fun JsonNode.canonical(out: StringBuilder) {
    when (this) {
        is ObjectNode -> {
            out.append('{')
            propertyNames().sorted().forEachIndexed { index, name ->
                if (index > 0) out.append(',')
                out.append(WowJson.mapper.writeValueAsString(name)).append(':')
                get(name).canonical(out)
            }
            out.append('}')
        }
        is ArrayNode -> {
            out.append('[')
            forEachIndexed { index, node ->
                if (index > 0) out.append(',')
                node.canonical(out)
            }
            out.append(']')
        }
        else -> out.append(WowJson.mapper.writeValueAsString(this))
    }
}

private const val PROBE_KEY = "probe"

/** The system fields every model shares, in the order a role is looked up. */
private val METADATA_FIELDS = SystemField.entries.filter { it != SystemField.IDENTITY }

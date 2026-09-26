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
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.descriptor.AggregationLimitsDescriptor
import me.ahoo.wow.api.query.descriptor.AnalysisDescriptor
import me.ahoo.wow.api.query.descriptor.AnalysisSortDescriptor
import me.ahoo.wow.api.query.descriptor.ConstraintDescriptor
import me.ahoo.wow.api.query.descriptor.DynamicFieldDescriptor
import me.ahoo.wow.api.query.descriptor.ElementDescriptor
import me.ahoo.wow.api.query.descriptor.EnumValueDescriptor
import me.ahoo.wow.api.query.descriptor.FieldAggregateDescriptor
import me.ahoo.wow.api.query.descriptor.FieldDescriptor
import me.ahoo.wow.api.query.descriptor.FieldFilterDescriptor
import me.ahoo.wow.api.query.descriptor.FieldSortDescriptor
import me.ahoo.wow.api.query.descriptor.HavingDescriptor
import me.ahoo.wow.api.query.descriptor.LimitsDescriptor
import me.ahoo.wow.api.query.descriptor.PagingMode
import me.ahoo.wow.api.query.descriptor.QueryModelDescriptor
import me.ahoo.wow.api.query.descriptor.RecordDescriptor
import me.ahoo.wow.api.query.descriptor.SearchDescriptor
import me.ahoo.wow.api.query.descriptor.SensitivityDescriptor
import me.ahoo.wow.api.query.descriptor.VariantDescriptor
import me.ahoo.wow.api.query.descriptor.VariantsDescriptor
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.spec.MetricSpec
import me.ahoo.wow.api.query.spec.OperatorCost
import me.ahoo.wow.api.query.spec.OperatorTarget
import me.ahoo.wow.api.query.spec.SystemField
import me.ahoo.wow.api.query.spec.spec
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.time.ZoneId
import java.util.HexFormat

/**
 * Describes how this model can be queried on one entry, derived from the same capability table and operator specs that
 * admission reads, so the description and admission cannot disagree.
 *
 * @param budget the entry's budget; `null` for an unbudgeted entry.
 * @param defaultListSize the list size an entry applies when a list query sends none.
 * @param timeZone the server's default time zone, used when a request names none.
 */
fun QueryModelSchema.describe(
    budget: QueryBudget?,
    defaultListSize: Int?,
    timeZone: ZoneId = ZoneId.systemDefault(),
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

    fun descriptor(defaultListSize: Int?, timeZone: ZoneId): QueryModelDescriptor {
        val paths = schema.definition.values.filterKeys { it.segments.isNotEmpty() }
        // Resolved exactly as admission resolves them: one entry per logical path, array items implicit.
        val fields = paths.keys.filter { it.keyCount == 0 && it.segments.last() != QueryPathSegment.Item }
            .map { it.logicalPath() }.distinct()
            .mapNotNull { path -> schema.field(QueryField(path))?.let(::field) }
        val cursor = identity != null && fields.any { it.path == identity && it.sort.cursor }
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
            variants = variants(),
        )
        return described.copy(version = versionOf(described))
    }

    private val elementPaths = mutableSetOf<String>()

    /**
     * Describes [field] with its capabilities. A variant's field passes its own [value] (the variant's facts at that
     * path), its element-relative [path] and [scope], and does not register elements.
     */
    private fun field(
        field: QueryFieldSchema,
        value: QueryValueSchema = field.value,
        path: String = field.logicalField.path,
        scope: String? = field.elementAncestors?.lastOrNull()?.path,
        variant: Boolean = false,
    ): FieldDescriptor? {
        val capabilities = field.capabilities
        val projectable = field.projectionField != null
        if (capabilities.isEmpty() && !projectable) return null
        if (!variant && QueryCapability.ELEMENT_SCOPE in capabilities) elementPaths += path
        val comparable = field.comparable
        val effective = if (value === field.value) field.effective else field.capabilitiesFor(value)
        return FieldDescriptor(
            path = path,
            role = role(path),
            types = value.typesInOrder(),
            kind = value.kind,
            nullable = value.nullable,
            semantic = value.semanticType,
            enum = value.enumValues?.takeUnless { field.protected }
                ?.map { EnumValueDescriptor(it, value.enumDescriptions[it]) },
            description = value.description,
            sensitivity = (value.maskRule?.level ?: field.protection?.takeUnless { value.kind == QueryValueKind.OBJECT })
                ?.let { SensitivityDescriptor(it, comparable) },
            project = projectable,
            filter = FieldFilterDescriptor(effective.operators(allowExpensive)),
            sort = FieldSortDescriptor(paged = effective.sortable, cursor = field.cursorSortable),
            aggregate = aggregate(effective),
            scope = scope,
            deprecated = schema.definition.deprecations[field.logicalField],
            aliases = aliasesOf(field.logicalField, variant),
        )
    }

    private val aliasesByField: Map<QueryField, List<String>> by lazy {
        schema.definition.aliases.entries.groupBy({ it.value }, { it.key.path }).mapValues { it.value.sorted() }
    }

    /** A variant field lists its aliases relative to the variant element, as it lists its own path. */
    private fun aliasesOf(field: QueryField, variant: Boolean): List<String> {
        val aliases = aliasesByField[field].orEmpty()
        val element = schema.profile?.variantElement?.path
        return if (variant && element != null) aliases.map { it.removePrefix("$element.") } else aliases
    }

    /**
     * The EventStream `body` variants: one per event type the payload was inferred from, each with its own fields
     * relative to the element, and each field's capabilities as admission resolves the shared logical path.
     */
    private fun variants(): VariantsDescriptor? {
        val profile = schema.profile ?: return null
        val element = profile.variantElement?.path ?: return null
        val payload = schema.definition.value(profile.payloadField.toPathTemplate()) ?: return null
        val variants = if (payload.variant != null) {
            listOf(
                payload
            )
        } else {
            payload.alternatives.filter { it.variant != null }
        }
        if (variants.isEmpty()) return null
        val payloadName = profile.payloadField.path.removePrefix("$element.")
        return VariantsDescriptor(
            element = element,
            discriminator = checkNotNull(profile.payloadTypeField).path.removePrefix("$element."),
            values = variants.map { variant ->
                VariantDescriptor(
                    value = checkNotNull(variant.variant),
                    description = variant.description,
                    fields = variantFields(variant, profile.payloadField, element, payloadName),
                )
            }.sortedBy { it.value },
        )
    }

    private fun variantFields(
        variant: QueryValueSchema,
        payloadField: QueryField,
        element: String,
        payloadName: String,
    ): List<FieldDescriptor> = variant.valuePaths()
        .map { it.first }
        .filter { it.segments.isNotEmpty() && it.keyCount == 0 && it.segments.last() != QueryPathSegment.Item }
        .map { it.logicalPath() }.distinct()
        .mapNotNull { relative ->
            val shared = schema.field(QueryField("${payloadField.path}.$relative")) ?: return@mapNotNull null
            val own = mergeQueryValues(variant.lookup(QueryField(relative).toPathTemplate())) ?: return@mapNotNull null
            val scope = shared.elementAncestors?.lastOrNull()?.path
                ?.takeUnless { it == element }?.removePrefix("$element.")
            field(shared, own, "$payloadName.$relative", scope, variant = true)
        }
        .sortedBy { it.path }

    private fun aggregate(effective: QueryFieldCapabilities): FieldAggregateDescriptor? {
        if (!effective.aggregatable) return null
        val metrics = effective.metrics(allowExpensive)
        return FieldAggregateDescriptor(
            groups = effective.groups(allowExpensive).map { it.name },
            missingKey = effective.missingKey,
            functions = if (MetricSpec.NUMERIC in metrics) AggregationFunction.entries.map { it.name } else emptyList(),
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
     * resolves a concrete key, so an array pattern and its items yield one entry.
     */
    private fun dynamic(path: QueryPathTemplate): DynamicFieldDescriptor? {
        val excluded = schema.definition.keyExclusions(path).values.flatten().toSet()
        val probe = generateSequence(PROBE_KEY) { "_$it" }.first { it !in excluded }
        val field = schema.field(path.field(List(path.keyCount) { probe })) ?: return null
        if (field.capabilities.isEmpty()) return null
        val value = field.value
        val operators = field.effective.grantedOperators
        return DynamicFieldDescriptor(
            pattern = path.logicalPath(),
            types = value.typesInOrder(),
            kind = value.kind,
            filter = FieldFilterDescriptor(
                if (allowExpensive) operators else operators.filter { it.spec.baseCost != OperatorCost.EXPENSIVE },
            ),
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
                    systemPath(checkNotNull(spec.systemField))?.let { byPath[it] }?.filter?.operators?.isNotEmpty() == true
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

    private fun systemPath(field: SystemField): String? =
        if (field == SystemField.IDENTITY) identity else QueryModelProfile.metadataField(field).path

    /** The system role of [path]: a metadata field's own, or the identity's for the model's identity field. */
    private fun role(path: String): String? = METADATA_FIELDS.firstOrNull { systemPath(it) == path }?.name
        ?: SystemField.IDENTITY.name.takeIf { path == identity }

    private fun limits(defaultListSize: Int?): LimitsDescriptor {
        fun Int.limit(): Int? = takeIf { it > 0 }
        val maxList = budget?.maxListSize?.limit()
        return LimitsDescriptor(
            maxListSize = maxList,
            defaultListSize = defaultListSize?.limit()?.let { if (maxList == null) it else minOf(it, maxList) },
            maxPageSize = budget?.maxPageSize?.limit(),
            maxPageWindow = budget?.maxPageWindow?.takeIf { it > 0 },
            maxFilterNodes = budget?.maxFilterNodes?.limit(),
            maxFilterValues = budget?.maxFilterValues?.limit(),
            maxSortFields = AggregationQuery.MAX_SORT_FIELDS,
            aggregation = AggregationLimitsDescriptor(
                maxGroups = AggregationQuery.MAX_GROUPS,
                maxMetrics = AggregationQuery.MAX_METRICS,
                maxElements = AggregationQuery.MAX_ELEMENTS,
                maxLimit = maxList?.let { minOf(it, AggregationQuery.MAX_LIMIT) } ?: AggregationQuery.MAX_LIMIT,
                maxExpressionDepth = AggregationQuery.MAX_EXPRESSION_DEPTH,
                maxExpressionNodes = AggregationQuery.MAX_EXPRESSION_NODES,
            ),
        )
    }

    private fun analysis(): AnalysisDescriptor {
        // FIRST and LAST need storage support and, being expensive, an entry that allows expensive operations.
        // DERIVED stays listed: `expressions` states whether its arithmetic is allowed.
        val firstLast = schema.storage.aggregation.firstLast != SupportMode.NONE && allowExpensive
        val specs = MetricSpec.entries.filter { metric ->
            when (metric) {
                MetricSpec.FIRST, MetricSpec.LAST -> firstLast
                else -> true
            }
        }
        val metrics = specs.map { it.name }
        return AnalysisDescriptor(
            metrics = metrics,
            approximate = metrics.filter { it in schema.approximateMetrics },
            expressions = allowExpensive,
            having = HavingDescriptor(specs.filter { it.havingOperand }.map { it.name }),
            sort = AnalysisSortDescriptor(groups = true, metrics = allowExpensive),
            // Dense fill materializes every bucket of the range, an expensive group (GroupSpec.cost).
            dense = allowExpensive,
            dateUnits = AggregationDateUnit.entries,
            dateParts = AggregationDatePart.entries,
            dateDiffUnits = if (allowExpensive) DateDiffUnit.entries else emptyList(),
            firstLastOrderBy = schema.profile?.eventTimeField?.path?.takeIf { firstLast },
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
                appended = it
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

/** `sha256:` of the canonical JSON: object members sorted by name, so equal content always has equal versions. */
private fun versionOf(descriptor: QueryModelDescriptor): String {
    val canonical = StringBuilder().also { JsonSerializer.valueToTree<JsonNode>(descriptor).canonical(it) }.toString()
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return "sha256:" + HexFormat.of().formatHex(digest)
}

private fun JsonNode.canonical(out: StringBuilder) {
    when (this) {
        is ObjectNode -> {
            out.append('{')
            propertyNames().sorted().forEachIndexed { index, name ->
                if (index > 0) out.append(',')
                out.append(JsonSerializer.writeValueAsString(name)).append(':')
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
        else -> out.append(JsonSerializer.writeValueAsString(this))
    }
}

private const val PROBE_KEY = "probe"

/** The system fields every model shares, in the order a role is looked up. */
private val METADATA_FIELDS = SystemField.entries.filter { it != SystemField.IDENTITY }

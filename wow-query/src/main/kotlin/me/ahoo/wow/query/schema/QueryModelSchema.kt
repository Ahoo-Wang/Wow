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

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryDeprecation
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import java.util.Collections

private val EMPTY_VALUE_BINDINGS = QueryValueBindings()

private val QUERY_STORAGE_TYPE_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_-]*")

data class QueryStorageType(val value: String) {
    init { require(QUERY_STORAGE_TYPE_PATTERN.matches(value)) }
}

class QueryFieldBinding(val physicalField: QueryField, storageTypes: Set<QueryStorageType>?) {
    val storageTypes: Set<QueryStorageType>? = storageTypes?.let { Collections.unmodifiableSet(LinkedHashSet(it)) }

    init { require(this.storageTypes == null || this.storageTypes.isNotEmpty()) }

    override fun equals(other: Any?): Boolean = other is QueryFieldBinding &&
        physicalField == other.physicalField && storageTypes == other.storageTypes
    override fun hashCode(): Int = 31 * physicalField.hashCode() + (storageTypes?.hashCode() ?: 0)
}

/** A logical definition is shared unchanged with every native binding snapshot. */
class LogicalQuerySchema(
    val root: QueryValueSchema,
    val sensitivity: QuerySensitivityPolicy = QuerySensitivityPolicy.DEFAULT,
) {
    val values: Map<QueryPathTemplate, QueryValueSchema>
    internal val maskedValues: List<Pair<QueryPathTemplate, QueryValueSchema>>
    internal val staticMatches: Map<QueryField, List<QueryValueMatch>>

    /** Each alias and the canonical logical field it names. */
    val aliases: Map<QueryField, QueryField>

    /** The deprecated logical fields. */
    val deprecations: Map<QueryField, QueryDeprecation>

    init {
        val paths = root.valuePaths()
        val hasUnions = paths.any { it.second.kind == QueryValueKind.UNION }
        values = Collections.unmodifiableMap(
            linkedMapOf<QueryPathTemplate, QueryValueSchema>().apply {
                paths.forEach { (path, value) -> putIfAbsent(path, value) }
                if (hasUnions) replaceAll { path, _ -> checkNotNull(mergeQueryValues(root.lookup(path))) }
            },
        )
        maskedValues = paths.filter { it.second.maskRule != null }
        staticMatches = buildMap {
            this@LogicalQuerySchema.values.forEach { (path, value) ->
                if (path.keyCount != 0 || path.segments.none { it is QueryPathSegment.Property }) return@forEach
                val field = path.field(emptyList())
                if (containsKey(field)) return@forEach
                val matches = if (hasUnions) {
                    root.lookup(field.toPathTemplate())
                } else {
                    val ancestors = path.segments.mapIndexedNotNull { index, segment ->
                        if (segment == QueryPathSegment.Item) QueryPathTemplate(path.segments.subList(0, index)) else null
                    }
                    listOf(QueryValueMatch(value, emptyList(), ancestors, true, path))
                }
                put(field, matches)
            }
        }
        val named = paths.filter { (_, value) -> value.aliases.isNotEmpty() || value.deprecated != null }
        named.forEach { (path, _) ->
            if (path.keyCount != 0) {
                throw QuerySchemaConflictException("Aliases and deprecation cannot apply under a dynamic key.")
            }
        }
        deprecations = named.mapNotNull { (path, value) -> value.deprecated?.let { path.field(emptyList()) to it } }
            .toMap()
        aliases = buildMap {
            named.forEach { (path, value) ->
                val canonical = path.field(emptyList())
                value.aliases.forEach { alias ->
                    if (alias in staticMatches || putIfAbsent(alias, canonical)?.takeIf { it != canonical } != null) {
                        throw QuerySchemaConflictException("Query field alias [$alias] names another field.")
                    }
                }
            }
        }
    }

    fun value(path: QueryPathTemplate): QueryValueSchema? = values[path] ?: mergeQueryValues(root.lookup(path))

    /**
     * The canonical logical field [field] names: the field itself, or the canonical field of the alias it equals or
     * starts with (`state.oldName.city` → `state.newName.city`).
     */
    fun canonical(field: QueryField): QueryField {
        if (aliases.isEmpty()) return field
        aliases[field]?.let { return it }
        var end = field.path.lastIndexOf('.')
        while (end > 0) {
            val prefix = field.path.substring(0, end)
            aliases[QueryField(prefix)]?.let { return QueryField(it.path + field.path.substring(end)) }
            end = field.path.lastIndexOf('.', end - 1)
        }
        return field
    }
}

/** Published facts only: model values and operation-specific native locations. */
class QueryModelSchema(
    val model: QueryModel,
    capabilities: Set<QueryCapability>,
    val definition: LogicalQuerySchema,
    bindings: Map<QueryPathTemplate, QueryValueBindings>,
    /** Whether native storage can deliver an unrestricted source projection. */
    val fullProjectionAvailable: Boolean = true,
    /**
     * The metric types (`DISTINCT_COUNT`, `PERCENTILE`) the backend estimates rather than computes exactly, published
     * so consumers can label their results.
     */
    approximateMetrics: Set<String> = emptySet(),
    /** How the storage pages and aggregates, as its adapter declares it. */
    val storage: StorageSupport = StorageSupport.NATIVE,
) {
    val approximateMetrics: Set<String> = Collections.unmodifiableSet(LinkedHashSet(approximateMetrics))
    val capabilities: Set<QueryCapability> = Collections.unmodifiableSet(LinkedHashSet(capabilities))
    val root: QueryValueSchema
        get() = definition.root
    val bindings: Map<QueryPathTemplate, QueryValueBindings> = Collections.unmodifiableMap(
        LinkedHashMap<QueryPathTemplate, QueryValueBindings>().apply {
            definition.values.keys.forEach { put(it, bindings[it] ?: EMPTY_VALUE_BINDINGS) }
            putAll(bindings)
        },
    )
    private val bindingIndex = QueryBindingIndex(this.bindings)
    private val staticFields: Map<QueryField, QueryFieldSchema?> = definition.staticMatches.mapValues { (field, matches) ->
        resolveField(field, matches)
    }
    internal val maskedValues = definition.maskedValues
    internal val hasMaskedFields: Boolean = maskedValues.isNotEmpty()

    /**
     * Whether some field's raw value must not be compared. Model-wide search then cannot be admitted: it matches
     * every searchable field, and storage decides which fields those are.
     */
    val hasIncomparableFields: Boolean = maskedValues.any {
        !definition.sensitivity.comparable(it.second.maskRule?.level)
    }
    internal val protectedSources = QueryProtectedSources(this)
    internal val maskDefinition = QueryMaskDefinition.create(this)

    init {
        require(APPROXIMABLE_METRICS.containsAll(approximateMetrics)) {
            "Only $APPROXIMABLE_METRICS can be approximate: $approximateMetrics."
        }
        bindings.forEach { (path, native) ->
            require(definition.value(path) != null) { "Native binding has no logical value: [${path.segments}]." }
            val keyCount = path.keyCount
            native.bindings.values.forEach { requireNativeKeyCount(it.physicalPath, keyCount) }
            native.projectionPath?.let { requireNativeKeyCount(it, keyCount) }
            native.responsePath?.let { requireNativeKeyCount(it, keyCount) }
        }
    }

    private fun requireNativeKeyCount(path: QueryPathTemplate, keyCount: Int) {
        require(path.keyCount == keyCount) { "Native path must retain each logical map key." }
    }

    fun supports(capability: QueryCapability): Boolean = capability in capabilities

    /** The content hash of what this schema lets callers do: the version of its budget-free capability descriptor. */
    val version: String by lazy { describe(budget = null, defaultListSize = null).version }

    /** The logical paths of the masked fields, in declaration order. */
    val maskedFields: List<String> by lazy { maskedValues.map { it.first.logicalPath() }.distinct() }

    /** Whether some field has an alias a query may use instead of its canonical path. */
    val hasAliases: Boolean
        get() = definition.aliases.isNotEmpty()

    fun field(field: QueryField): QueryFieldSchema? =
        if (staticFields.containsKey(field)) staticFields[field] else resolveField(field)

    private fun resolveField(field: QueryField): QueryFieldSchema? = resolveField(
        field,
        root.lookup(field.toPathTemplate())
    )

    private fun resolveField(field: QueryField, matches: List<QueryValueMatch>): QueryFieldSchema? {
        if (matches.size == 1) return resolveSingleField(field, matches.single())
        val value = mergeQueryValues(matches) ?: return null
        val locations = matches.map { match ->
            if (!match.complete) return@map null
            bindingIndex.locate(match.path)
        }
        val fields = locations.map { located ->
            located?.let { (native, keys) ->
                native.bindings.mapValues { (_, binding) ->
                    QueryFieldBinding(binding.physicalPath.field(keys), binding.storageTypes)
                }
            }.orEmpty()
        }
        val common = fields.firstOrNull().orEmpty().mapNotNull { (capability, first) ->
            val alternatives = fields.map { it[capability] ?: return@mapNotNull null }
            if (alternatives.any { it.physicalField != first.physicalField }) return@mapNotNull null
            val types = if (alternatives.any { it.storageTypes == null }) {
                null
            } else {
                alternatives.flatMapTo(linkedSetOf()) { checkNotNull(it.storageTypes) }
            }
            capability to QueryFieldBinding(first.physicalField, types)
        }.toMap()
        val scopes = matches.map { it.elementAncestors.map { ancestor -> ancestor.field(emptyList()) } }.distinct()
        return QueryFieldSchema(
            schema = this,
            logicalField = field,
            value = value,
            elementAncestors = scopes.singleOrNull(),
            bindings = common,
            projectionField = locations.map { it?.let { (native, keys) -> native.projectionPath?.field(keys) } }
                .distinct().singleOrNull(),
            responseField = locations.map { it?.let { (native, keys) -> native.responsePath?.field(keys) } }
                .distinct().singleOrNull(),
        )
    }

    private fun resolveSingleField(field: QueryField, match: QueryValueMatch): QueryFieldSchema? {
        if (!match.complete) return null
        val location = bindingIndex.locate(match.path)
        val native = location?.first
        val keys = location?.second.orEmpty()
        val shared = HashMap<QueryFieldBindingTemplate, QueryFieldBinding>()
        val bindings = native?.bindings?.mapValues { (_, template) ->
            shared.getOrPut(template) { QueryFieldBinding(template.physicalPath.field(keys), template.storageTypes) }
        }.orEmpty()
        return QueryFieldSchema(
            this,
            field,
            match.value,
            match.elementAncestors.map { it.field(emptyList()) },
            bindings,
            native?.projectionPath?.field(keys),
            native?.responsePath?.field(keys),
        )
    }
}

/**
 * One resolved field of a [QueryModelSchema] with its capability facts. Facts that depend only on the field and its
 * schema are computed once per instance; static fields are resolved once per schema.
 */
class QueryFieldSchema internal constructor(
    private val schema: QueryModelSchema,
    val logicalField: QueryField,
    val value: QueryValueSchema,
    elementAncestors: List<QueryField>?,
    bindings: Map<QueryCapability, QueryFieldBinding>,
    val projectionField: QueryField?,
    val responseField: QueryField?,
) {
    val elementAncestors: List<QueryField>? = elementAncestors?.let { java.util.List.copyOf(it) }
    val bindings: Map<QueryCapability, QueryFieldBinding> = Collections.unmodifiableMap(LinkedHashMap(bindings))
    val capabilities: Set<QueryCapability>
        get() = bindings.keys
    fun binding(capability: QueryCapability): QueryFieldBinding? = bindings[capability]

    /**
     * The strongest sensitivity level protecting any source of this field, or `null` when none does. Protected fields
     * cannot be aggregated or cursor-sorted.
     */
    val protection: SensitivityLevel? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        fieldProtection(schema, logicalField, this)
    }

    /** Whether a sensitivity level protects any source of this field. */
    val protected: Boolean
        get() = protection != null

    /** Whether filters and paged sorts may compare this field's raw value. */
    val comparable: Boolean
        get() = schema.definition.sensitivity.comparable(protection)

    /** Whether this field can order a cursor: cursor-sortable storage, single-valued, top level and unprotected. */
    val cursorSortable: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        binding(QueryCapability.CURSOR_SORT) != null && value.cardinality == QueryCardinality.SINGLE &&
            elementAncestors == emptyList<QueryField>() && !protected
    }
}

internal fun mergeQueryValues(matches: List<QueryValueMatch>): QueryValueSchema? {
    if (matches.none { it.complete }) return null
    val values = matches.map { it.value }.distinct()
    if (values.size == 1) return values.single()
    return QueryValueSchema(kind = QueryValueKind.UNION, alternatives = values)
}

/** The metric types whose results a backend may estimate. */
internal val APPROXIMABLE_METRICS: Set<String> = setOf("DISTINCT_COUNT", "PERCENTILE")

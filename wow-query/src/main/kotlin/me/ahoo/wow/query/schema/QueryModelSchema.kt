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
import me.ahoo.wow.api.query.schema.QueryCapability
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
class LogicalQuerySchema(val root: QueryValueSchema) {
    val values: Map<QueryPathTemplate, QueryValueSchema>
    internal val maskedValues: List<Pair<QueryPathTemplate, QueryValueSchema>>
    internal val staticMatches: Map<QueryField, List<QueryValueMatch>>

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
    }

    fun value(path: QueryPathTemplate): QueryValueSchema? = values[path] ?: mergeQueryValues(root.lookup(path))
}

/** Published facts only: model values and operation-specific native locations. */
class QueryModelSchema(
    val model: QueryModel,
    capabilities: Set<QueryCapability>,
    val definition: LogicalQuerySchema,
    bindings: Map<QueryPathTemplate, QueryValueBindings>,
    /** Whether native storage can deliver an unrestricted source projection. */
    val fullProjectionAvailable: Boolean = true,
) {
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
    internal val protectedSources = QueryProtectedSources(this)
    internal val maskDefinition = me.ahoo.wow.query.mask.QueryMaskDefinition.create(this)

    init {
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
            field,
            match.value,
            match.elementAncestors.map { it.field(emptyList()) },
            bindings,
            native?.projectionPath?.field(keys),
            native?.responsePath?.field(keys),
        )
    }
}

class QueryFieldSchema(
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
}

internal fun mergeQueryValues(matches: List<QueryValueMatch>): QueryValueSchema? {
    if (matches.none { it.complete }) return null
    val values = matches.map { it.value }.distinct()
    if (values.size == 1) return values.single()
    return QueryValueSchema(kind = QueryValueKind.UNION, alternatives = values)
}

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

import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import reactor.core.publisher.Mono
import tools.jackson.databind.JavaType
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.introspect.BeanPropertyDefinition
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.math.BigDecimal
import java.math.BigInteger
import java.time.temporal.Temporal
import java.time.temporal.TemporalAccessor
import java.util.Collections
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter

class JacksonQuerySchemaResolver(
    private val objectMapper: ObjectMapper,
    aggregateMetadata: Collection<AggregateMetadata<*, *>>,
    customizers: List<QuerySchemaCustomizer> = emptyList()
) : QuerySchemaResolver {
    private val metadataByAggregate: Map<AggregateKey, AggregateMetadata<*, *>> = aggregateMetadata
        .associateByUnique { AggregateKey(it.namedAggregate.contextName, it.namedAggregate.aggregateName) }
    private val customizers: List<QuerySchemaCustomizer> = Collections.unmodifiableList(ArrayList(customizers))
    private val cache = ConcurrentHashMap<CacheKey, QuerySchemaView>()

    override fun resolve(target: QueryTarget): Mono<QuerySchemaView> = Mono.defer {
        Mono.fromSupplier {
            val metadata = metadataByAggregate[AggregateKey(target.namedAggregate.contextName, target.namedAggregate.aggregateName)]
                ?: throw QuerySchemaException(QuerySchemaErrorReason.METADATA_NOT_FOUND)
            val key = CacheKey(target, MetadataIdentity(metadata))
            cache.computeIfAbsent(key) {
                derive(target, metadata)
            }
        }
    }

    private fun derive(target: QueryTarget, metadata: AggregateMetadata<*, *>): QuerySchema {
        val fields = ArrayList(QuerySystemFields.fields(target.documentKind))
        try {
            if (target.documentKind == QueryDocumentKind.SNAPSHOT) {
                val stateType = objectMapper.constructType(metadata.state.aggregateType)
                deriveProperties(
                    rootType = stateType,
                    prefix = "state",
                    fields = fields,
                    visiting = LinkedHashSet()
                )
            }
        } catch (error: QuerySchemaException) {
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            throw QuerySchemaException(QuerySchemaErrorReason.INTROSPECTION_FAILED)
        }
        val base = QuerySchema(target, fields)
        return customize(base)
    }

    private fun deriveProperties(
        rootType: JavaType,
        prefix: String,
        fields: MutableList<QueryFieldSchema>,
        visiting: MutableSet<Class<*>>
    ) {
        val identity = rootType.rawClass
        if (!visiting.add(identity)) {
            throw QuerySchemaException(QuerySchemaErrorReason.RECURSIVE_TYPE)
        }
        try {
            beanProperties(rootType).forEach { property ->
                addProperty(
                    ownerType = rootType,
                    property = property,
                    path = "$prefix.${property.name}",
                    fields = fields,
                    visiting = visiting
                )
            }
        } finally {
            visiting.remove(identity)
        }
    }

    private fun addProperty(
        ownerType: JavaType,
        property: BeanPropertyDefinition,
        path: String,
        fields: MutableList<QueryFieldSchema>,
        visiting: MutableSet<Class<*>>
    ) {
        val type = property.primaryType
        val nullable = property.isKotlinNullable(ownerType.rawClass)
        when {
            type.isMapLikeType -> fields += QueryFieldSchema(
                path = LogicalField(path),
                valueKind = QueryFieldValueKind.MAP,
                nullable = nullable,
                queryable = false
            )

            type.rawClass == ByteArray::class.java -> fields += QueryFieldSchema(
                path = LogicalField(path),
                valueKind = QueryFieldValueKind.BINARY,
                nullable = nullable
            )

            type.isCollectionLikeType || type.isArrayType -> addCollection(
                type = type,
                path = path,
                nullable = nullable,
                fields = fields,
                visiting = visiting
            )

            type.scalarKind() != null -> fields += QueryFieldSchema(
                path = LogicalField(path),
                valueKind = checkNotNull(type.scalarKind()),
                nullable = nullable
            )

            else -> {
                fields += QueryFieldSchema(
                    path = LogicalField(path),
                    valueKind = QueryFieldValueKind.OBJECT,
                    nullable = nullable,
                    queryable = false,
                    operators = emptySet()
                )
                deriveProperties(type, path, fields, visiting)
            }
        }
    }

    private fun addCollection(
        type: JavaType,
        path: String,
        nullable: Boolean,
        fields: MutableList<QueryFieldSchema>,
        visiting: MutableSet<Class<*>>
    ) {
        val contentType = type.contentType ?: throw QuerySchemaException(QuerySchemaErrorReason.INTROSPECTION_FAILED)
        val contentKind = contentType.scalarKind()
        if (contentKind != null) {
            fields += QueryFieldSchema(
                path = LogicalField(path),
                valueKind = contentKind,
                nullable = nullable,
                collectionKind = QueryCollectionKind.SCALAR,
                sortable = false
            )
            return
        }
        fields += QueryFieldSchema(
            path = LogicalField(path),
            valueKind = QueryFieldValueKind.OBJECT,
            nullable = nullable,
            collectionKind = QueryCollectionKind.OBJECT,
            sortable = false,
            elementMatchEnabled = false
        )
        deriveProperties(contentType, path, fields, visiting)
    }

    private fun beanProperties(type: JavaType): List<BeanPropertyDefinition> {
        val config = objectMapper.serializationConfig()
        val introspector = config.classIntrospectorInstance()
        return introspector.introspectForSerialization(type, introspector.introspectClassAnnotations(type))
            .findProperties()
            .filter(BeanPropertyDefinition::couldSerialize)
            .sortedBy(BeanPropertyDefinition::getName)
    }

    private fun BeanPropertyDefinition.isKotlinNullable(rootClass: Class<*>): Boolean {
        val member = accessor?.member ?: return !isRequired
        val property = rootClass.kotlin.memberProperties.firstOrNull { candidate ->
            when (member) {
                is Method -> candidate.javaGetter == member
                is Field -> candidate.javaField == member
                else -> false
            }
        }
        return property?.returnType?.isMarkedNullable ?: !isRequired
    }

    private fun customize(base: QuerySchema): QuerySchema {
        if (customizers.isEmpty()) {
            return base
        }
        val context = QuerySchemaCustomizationContext(base.target, base)
        val results = customizers.map { customizer ->
            try {
                customizer.customize(context)
            } catch (error: QuerySchemaException) {
                throw error
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                throw QuerySchemaException(QuerySchemaErrorReason.CUSTOMIZATION_FAILED)
            }
        }
        return QuerySchemaCustomizationMerger.merge(base, results)
    }

    private fun JavaType.scalarKind(): QueryFieldValueKind? {
        val raw = rawClass
        return when {
            raw.isBooleanType() -> QueryFieldValueKind.BOOLEAN
            isEnumType -> QueryFieldValueKind.ENUM
            raw.isStringType() -> QueryFieldValueKind.STRING
            raw.isIntegerType() -> QueryFieldValueKind.INTEGER
            raw.isDecimalType() -> QueryFieldValueKind.DECIMAL
            raw.isTimeType() -> QueryFieldValueKind.TIME
            else -> null
        }
    }

    private fun Class<*>.isBooleanType(): Boolean =
        this == Boolean::class.java || this == Boolean::class.javaPrimitiveType

    private fun Class<*>.isStringType(): Boolean =
        this == String::class.java || this == Char::class.java || this == Char::class.javaPrimitiveType ||
            this == java.util.UUID::class.java

    private fun Class<*>.isIntegerType(): Boolean = this == Byte::class.java ||
        this == Byte::class.javaPrimitiveType || this == Short::class.java || this == Short::class.javaPrimitiveType ||
        this == Int::class.java || this == Int::class.javaPrimitiveType || this == Long::class.java ||
        this == Long::class.javaPrimitiveType || this == BigInteger::class.java

    private fun Class<*>.isDecimalType(): Boolean = Number::class.java.isAssignableFrom(this) ||
        this == Float::class.javaPrimitiveType || this == Double::class.javaPrimitiveType || this == BigDecimal::class.java

    private fun Class<*>.isTimeType(): Boolean = Date::class.java.isAssignableFrom(this) ||
        Temporal::class.java.isAssignableFrom(this) || TemporalAccessor::class.java.isAssignableFrom(this)

    private data class AggregateKey(val contextName: String, val aggregateName: String)

    private class MetadataIdentity(private val metadata: AggregateMetadata<*, *>) {
        override fun equals(other: Any?): Boolean = other is MetadataIdentity && metadata === other.metadata

        override fun hashCode(): Int = System.identityHashCode(metadata)
    }

    private data class CacheKey(val target: QueryTarget, val metadata: MetadataIdentity)
}

internal object QuerySchemaCustomizationMerger {
    fun merge(base: QuerySchema, customizedSchemas: List<QuerySchema>): QuerySchema {
        customizedSchemas.forEach { customized ->
            if (customized.target != base.target || !customized.fields.keys.containsAll(base.fields.keys)) {
                throw QuerySchemaException(QuerySchemaErrorReason.INVALID_CUSTOMIZATION)
            }
        }
        val changedByPath = sortedMapOf<String, MutableList<QueryFieldSchema>>()
        customizedSchemas.forEach { customized ->
            customized.fields.forEach { (path, field) ->
                if (field != base.fields[path]) {
                    changedByPath.getOrPut(path.value) { mutableListOf() }.add(field)
                }
            }
        }
        var merged = base
        changedByPath.forEach { (_, changes) ->
            val baseField = base.fields[changes.first().path]
            val field = if (baseField == null) {
                mergeAddition(changes)
            } else {
                mergeExisting(baseField, changes)
            }
            merged = conflictOnInvalidDescriptor { merged.withField(field) }
        }
        return merged
    }

    private fun mergeAddition(changes: List<QueryFieldSchema>): QueryFieldSchema {
        val first = changes.first()
        if (first.system || changes.any { it.hasDifferentIdentityFrom(first) }) {
            throw QuerySchemaException(QuerySchemaErrorReason.CUSTOMIZER_CONFLICT)
        }
        val bindings = changes.flatMap(QueryFieldSchema::bindings).groupBy(QueryCapabilityBinding::key)
        validateBindingChanges(bindings)
        val stringOptions = changes.map(QueryFieldSchema::stringOptions).distinct()
        validateStringOptionChanges(stringOptions)
        return conflictOnInvalidDescriptor {
            first.copy(
                queryable = changes.all(QueryFieldSchema::queryable),
                sortable = changes.all(QueryFieldSchema::sortable),
                projectable = changes.all(QueryFieldSchema::projectable),
                elementMatchEnabled = changes.any(QueryFieldSchema::elementMatchEnabled),
                operators = changes.map(QueryFieldSchema::operators).reduce(Set<PortableOperator>::intersect),
                capabilities = changes.flatMap(QueryFieldSchema::capabilities).toSet(),
                bindings = bindings.values.map { it.first() }.toSet(),
                stringOptions = stringOptions.single()
            )
        }
    }

    private fun mergeExisting(base: QueryFieldSchema, changes: List<QueryFieldSchema>): QueryFieldSchema {
        if (changes.any { change -> change.isInvalidModificationOf(base) }) {
            throw QuerySchemaException(QuerySchemaErrorReason.INVALID_CUSTOMIZATION)
        }
        val stringOptionChanges = changes.map(QueryFieldSchema::stringOptions)
            .filter { it != base.stringOptions }
            .distinct()
        validateStringOptionChanges(stringOptionChanges)

        val operatorsRemoved = changes.flatMap { base.operators - it.operators }.toSet()
        val capabilitiesRemoved = changes.flatMap { base.capabilities - it.capabilities }.toSet()
        val capabilitiesAdded = changes.flatMap { it.capabilities - base.capabilities }.toSet()
        return conflictOnInvalidDescriptor {
            base.copy(
                queryable = base.queryable && changes.all(QueryFieldSchema::queryable),
                sortable = base.sortable && changes.all(QueryFieldSchema::sortable),
                projectable = base.projectable && changes.all(QueryFieldSchema::projectable),
                elementMatchEnabled = if (base.elementMatchEnabled) {
                    changes.all(QueryFieldSchema::elementMatchEnabled)
                } else {
                    changes.any(QueryFieldSchema::elementMatchEnabled)
                },
                operators = base.operators - operatorsRemoved,
                capabilities = (base.capabilities - capabilitiesRemoved) + capabilitiesAdded,
                bindings = mergeExistingBindings(base.bindings, changes),
                stringOptions = stringOptionChanges.singleOrNull() ?: base.stringOptions
            )
        }
    }

    private fun mergeExistingBindings(
        baseBindings: Set<QueryCapabilityBinding>,
        changes: List<QueryFieldSchema>
    ): Set<QueryCapabilityBinding> {
        val baseByKey = baseBindings.associateBy(QueryCapabilityBinding::key)
        val result = LinkedHashMap(baseByKey)
        baseByKey.forEach { (key, baseBinding) ->
            val values = changes.map { change -> change.bindings.singleOrNull { it.key() == key } }
            val removes = values.any { it == null }
            val modifications = values.filterNotNull().filter { it != baseBinding }.distinct()
            if ((removes && modifications.isNotEmpty()) || modifications.size > 1) {
                throw QuerySchemaException(QuerySchemaErrorReason.CUSTOMIZER_CONFLICT)
            }
            when {
                removes -> result.remove(key)
                modifications.isNotEmpty() -> result[key] = modifications.single()
            }
        }
        val additions = changes.flatMap(QueryFieldSchema::bindings)
            .filter { it.key() !in baseByKey }
            .groupBy(QueryCapabilityBinding::key)
        validateBindingChanges(additions)
        additions.forEach { (key, bindings) -> result[key] = bindings.first() }
        return result.values.toSet()
    }

    private fun QueryFieldSchema.isInvalidModificationOf(base: QueryFieldSchema): Boolean =
        hasDifferentIdentityFrom(base) || widensAccessFrom(base) || enablesInvalidElementMatch() ||
            operators.any { it !in base.operators }

    private fun QueryFieldSchema.hasDifferentIdentityFrom(base: QueryFieldSchema): Boolean =
        path != base.path || valueKind != base.valueKind || nullable != base.nullable ||
            collectionKind != base.collectionKind || nested != base.nested || system != base.system

    private fun QueryFieldSchema.widensAccessFrom(base: QueryFieldSchema): Boolean =
        (!base.queryable && queryable) || (!base.sortable && sortable) || (!base.projectable && projectable)

    private fun QueryFieldSchema.enablesInvalidElementMatch(): Boolean =
        elementMatchEnabled && collectionKind != QueryCollectionKind.OBJECT

    private fun validateBindingChanges(changes: Map<QueryBindingKey, List<QueryCapabilityBinding>>) {
        if (changes.values.any { bindings -> bindings.distinct().size > 1 }) {
            throw QuerySchemaException(QuerySchemaErrorReason.CUSTOMIZER_CONFLICT)
        }
    }

    private fun validateStringOptionChanges(changes: List<StringQueryOptions?>) {
        if (changes.size > 1) {
            throw QuerySchemaException(QuerySchemaErrorReason.CUSTOMIZER_CONFLICT)
        }
    }

    private inline fun <T> conflictOnInvalidDescriptor(block: () -> T): T = try {
        block()
    } catch (_: IllegalArgumentException) {
        throw QuerySchemaException(QuerySchemaErrorReason.CUSTOMIZER_CONFLICT)
    }
}

private data class QueryBindingKey(val backendId: QueryBackendId, val usage: QueryFieldUsage)

private fun QueryCapabilityBinding.key(): QueryBindingKey = QueryBindingKey(backendId, usage)

private fun <T, K> Collection<T>.associateByUnique(keySelector: (T) -> K): Map<K, T> {
    val result = LinkedHashMap<K, T>(size)
    forEach { value ->
        require(result.put(keySelector(value), value) == null) { "Duplicate aggregate query schema metadata." }
    }
    require(result.size == size) { "Aggregate metadata cardinality changed during immutable snapshot." }
    return Collections.unmodifiableMap(result)
}

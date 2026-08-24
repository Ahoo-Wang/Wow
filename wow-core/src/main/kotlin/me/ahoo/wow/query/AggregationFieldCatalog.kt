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

package me.ahoo.wow.query

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonSubTypes
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toBeanDescription
import tools.jackson.databind.JavaType
import tools.jackson.databind.introspect.BeanPropertyDefinition
import tools.jackson.databind.ser.bean.BeanSerializerBase
import tools.jackson.databind.ser.impl.UnknownSerializer
import tools.jackson.databind.ser.std.ReferenceTypeSerializer
import tools.jackson.databind.ser.std.StdContainerSerializer
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAccessor
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class AggregationFieldCatalog(
    val paths: Map<String, AggregationField>,
) {
    val scalarPaths: Set<String> = paths.values.filterTo(linkedSetOf()) { it.kind == AggregationFieldKind.SCALAR }
        .mapTo(linkedSetOf(), AggregationField::path)
    val termsPaths: Set<String> = paths.values.filterTo(linkedSetOf()) { it.supportsTerms }
        .mapTo(linkedSetOf(), AggregationField::path)
    val numericPaths: Set<String> = paths.values.filterTo(linkedSetOf()) { it.isNumeric }
        .mapTo(linkedSetOf(), AggregationField::path)
    val temporalPaths: Set<String> = paths.values.filterTo(linkedSetOf()) { it.isTemporal }
        .mapTo(linkedSetOf(), AggregationField::path)
    val elementPaths: Set<String> = paths.values.filterTo(linkedSetOf()) {
        it.kind == AggregationFieldKind.OBJECT_COLLECTION && it.type.rawClass != Any::class.java
    }.mapTo(linkedSetOf(), AggregationField::path)

    companion object {
        private val defaultCatalogs = ConcurrentHashMap<Class<*>, AggregationFieldCatalog>()

        fun scan(
            stateType: Class<*>,
            maxDepth: Int = AggregationQuery.MAX_AGGREGATION_FIELD_DEPTH,
        ): AggregationFieldCatalog {
            require(maxDepth > 0) { "maxDepth must be greater than 0." }
            if (maxDepth == AggregationQuery.MAX_AGGREGATION_FIELD_DEPTH) {
                return defaultCatalogs.computeIfAbsent(stateType, ::scanDefault)
            }
            return scanUncached(stateType, maxDepth)
        }

        private fun scanDefault(stateType: Class<*>): AggregationFieldCatalog =
            scanUncached(stateType, AggregationQuery.MAX_AGGREGATION_FIELD_DEPTH)

        private fun scanUncached(stateType: Class<*>, maxDepth: Int): AggregationFieldCatalog {
            val snapshotType = JsonSerializer.typeFactory.constructParametricType(
                MaterializedSnapshot::class.java,
                stateType
            )
            val paths = linkedMapOf<String, AggregationField>()
            snapshotType.scan(paths, parent = "", depth = 1, maxDepth = maxDepth, collectionPaths = emptyList())
            return AggregationFieldCatalog(paths.toMap())
        }
    }
}

data class AggregationField(
    val path: String,
    val type: JavaType,
    val kind: AggregationFieldKind,
    val collectionPaths: List<String>,
) {
    val isNumeric: Boolean
        get() = type.isAggregationNumeric
    val isTemporal: Boolean
        get() = type.isAggregationDate
    val isTextual: Boolean
        get() = type.isAggregationTextual
    val isBoolean: Boolean
        get() = type.rawClass == Boolean::class.javaPrimitiveType || type.rawClass == Boolean::class.javaObjectType
    val usesStringLiteral: Boolean
        get() = isTemporal || isTextual || type.rawClass == UUID::class.java
    val supportsTerms: Boolean
        get() = type.isAggregationTerms
}

enum class AggregationFieldKind {
    SCALAR,
    OBJECT,
    OBJECT_COLLECTION,
    SCALAR_COLLECTION,
    UNSUPPORTED_COLLECTION,
}

private fun JavaType.scan(
    paths: MutableMap<String, AggregationField>,
    parent: String,
    depth: Int,
    maxDepth: Int,
    collectionPaths: List<String>,
) {
    if (depth > maxDepth) {
        return
    }
    rawClass.getAnnotation(JsonSubTypes::class.java)?.value?.forEach { subtype ->
        JsonSerializer.typeFactory.constructType(subtype.value.java)
            .scan(paths, parent, depth, maxDepth, collectionPaths)
    }
    toBeanDescription().findProperties().forEach { property ->
        if (property.couldSerialize()) {
            property.scan(paths, parent, depth, maxDepth, collectionPaths)
        }
    }
}

private fun BeanPropertyDefinition.scan(
    paths: MutableMap<String, AggregationField>,
    parent: String,
    depth: Int,
    maxDepth: Int,
    collectionPaths: List<String>,
) {
    val propertyType = primaryType
    val path = if (parent.isEmpty()) name else "$parent.$name"
    if (propertyType.isMapLikeType) return
    if (propertyType.isCollectionLikeType || propertyType.isArrayType) {
        val elementType = propertyType.contentType ?: return
        val scalarElementType = elementType.aggregationScalarType
        val nestedCollections = collectionPaths + path
        val kind = if (hasCustomCollectionSerialization) {
            AggregationFieldKind.UNSUPPORTED_COLLECTION
        } else {
            elementType.aggregationCollectionKind
        }
        paths[path] = AggregationField(path, scalarElementType ?: elementType, kind, nestedCollections)
        if (kind == AggregationFieldKind.OBJECT_COLLECTION) {
            elementType.scan(paths, path, depth + 1, maxDepth, nestedCollections)
        }
        return
    }
    if (hasCustomSerialization || propertyType.hasCustomSerialization) return

    val scalarType = propertyType.aggregationScalarType
    val kind = if (scalarType != null) AggregationFieldKind.SCALAR else AggregationFieldKind.OBJECT
    paths[path] = AggregationField(path, scalarType ?: propertyType, kind, collectionPaths)
    if (kind == AggregationFieldKind.OBJECT) {
        propertyType.scan(paths, path, depth + 1, maxDepth, collectionPaths)
    }
}

private val BeanPropertyDefinition.hasCustomSerialization: Boolean
    get() {
        val member = primaryMember ?: return false
        val config = JsonSerializer.serializationConfig()
        return config.annotationIntrospector.run {
            findSerializer(config, member) != null ||
                findSerializationConverter(config, member) != null ||
                member.getAnnotation(JsonFormat::class.java) != null ||
                findUnwrappingNameTransformer(config, member) != null
        }
    }

private val BeanPropertyDefinition.hasCustomCollectionSerialization: Boolean
    get() {
        if (hasCustomSerialization) return true
        val member = primaryMember ?: return false
        val config = JsonSerializer.serializationConfig()
        return config.annotationIntrospector.run {
            findContentSerializer(config, member) != null ||
                findSerializationContentConverter(config, member) != null
        }
    }

private val JavaType.aggregationCollectionKind: AggregationFieldKind
    get() = when {
        hasCustomSerialization -> AggregationFieldKind.UNSUPPORTED_COLLECTION
        aggregationScalarType != null -> AggregationFieldKind.SCALAR_COLLECTION
        isMapLikeType || isCollectionLikeType || isArrayType -> AggregationFieldKind.UNSUPPORTED_COLLECTION
        else -> AggregationFieldKind.OBJECT_COLLECTION
    }

private val JavaType.hasCustomSerialization: Boolean
    get() {
        val config = JsonSerializer.serializationConfig()
        val classInfo = toBeanDescription().classInfo
        if (config.annotationIntrospector.run {
                findSerializer(config, classInfo) != null || findSerializationConverter(config, classInfo) != null
            }
        ) {
            return true
        }
        if (aggregationScalarType != null) return false
        val serializer = runCatching {
            JsonSerializer._serializationContext().findValueSerializer(rawClass)
        }.getOrNull()
        return serializer != null && serializer !is BeanSerializerBase && serializer !is UnknownSerializer &&
            serializer !is StdContainerSerializer<*> && serializer !is ReferenceTypeSerializer<*>
    }

private val JavaType.aggregationScalarType: JavaType?
    get() {
        val jsonValueAccessor = toBeanDescription().findJsonValueAccessor()
        return if (jsonValueAccessor == null) {
            takeIf { it.isDirectAggregationScalar }
        } else {
            jsonValueAccessor.type.takeIf { it.isDirectAggregationScalar }
        }
    }

private val JavaType.isDirectAggregationScalar: Boolean
    get() = isAggregationNumeric || isAggregationDate || isAggregationTextual || rawClass.isPrimitive ||
        rawClass == Boolean::class.javaObjectType || rawClass == Char::class.javaObjectType ||
        rawClass == UUID::class.java

private val JavaType.isAggregationTextual: Boolean
    get() = rawClass.isEnum || CharSequence::class.java.isAssignableFrom(rawClass) ||
        rawClass == Char::class.javaPrimitiveType || rawClass == Char::class.javaObjectType

private val JavaType.isAggregationTerms: Boolean
    get() = !isAggregationDate && isDirectAggregationScalar &&
        rawClass != BigDecimal::class.java && rawClass != BigInteger::class.java

private val JavaType.isAggregationNumeric: Boolean
    get() = (rawClass.isPrimitive && rawClass != Boolean::class.javaPrimitiveType && rawClass != Char::class.javaPrimitiveType) ||
        Number::class.java.isAssignableFrom(rawClass)

private val JavaType.isAggregationDate: Boolean
    get() = Date::class.java.isAssignableFrom(rawClass) || rawClass in SUPPORTED_TEMPORAL_TYPES

private val SUPPORTED_TEMPORAL_TYPES: Set<Class<out TemporalAccessor>> = setOf(
    Instant::class.java,
    LocalDate::class.java,
    LocalDateTime::class.java,
    OffsetDateTime::class.java,
    ZonedDateTime::class.java,
)

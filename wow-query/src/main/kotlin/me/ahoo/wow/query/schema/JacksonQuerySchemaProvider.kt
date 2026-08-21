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

import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.PredicateOperator
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import tools.jackson.databind.JavaType
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.introspect.BeanPropertyDefinition
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.temporal.Temporal
import java.time.temporal.TemporalAccessor
import java.util.Date
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter

class JacksonQuerySchemaProvider(private val objectMapper: ObjectMapper) : QuerySchemaProvider {
    override fun getSchema(metadata: AggregateMetadata<*, *>): QuerySchema {
        val fields = CANONICAL_FIELDS.associateByTo(LinkedHashMap(), QueryFieldSchema::path)
        deriveProperties(
            type = objectMapper.constructType(metadata.state.aggregateType),
            prefix = "state",
            fields = fields,
            visiting = linkedSetOf()
        )
        return QuerySchema(fields)
    }

    private fun deriveProperties(
        type: JavaType,
        prefix: String,
        fields: MutableMap<LogicalField, QueryFieldSchema>,
        visiting: MutableSet<Class<*>>
    ) {
        require(visiting.add(type.rawClass)) { "Recursive state type is not supported by query schema." }
        try {
            beanProperties(type).forEach { property ->
                addProperty(type, property, "$prefix.${property.name}", fields, visiting)
            }
        } finally {
            visiting.remove(type.rawClass)
        }
    }

    private fun addProperty(
        ownerType: JavaType,
        property: BeanPropertyDefinition,
        path: String,
        fields: MutableMap<LogicalField, QueryFieldSchema>,
        visiting: MutableSet<Class<*>>
    ) {
        val type = property.primaryType
        val nullable = property.isKotlinNullable(ownerType.rawClass)
        val field = LogicalField(path)
        when {
            type.isMapLikeType -> fields[field] = QueryFieldSchema(field, QueryValueKind.MAP, nullable, queryable = false)
            type.rawClass == ByteArray::class.java -> fields[field] = QueryFieldSchema(field, QueryValueKind.BINARY, nullable)
            type.isEncodedTemporalString() -> fields[field] = QueryFieldSchema(
                field,
                QueryValueKind.STRING,
                nullable,
                operators = QueryFieldSchema.defaultOperators(QueryValueKind.STRING, QueryCollectionKind.NONE) -
                    STRING_PATTERN_OPERATORS,
                fullText = false
            )
            type.isCollectionLikeType || type.isArrayType -> addCollection(type, field, nullable, fields, visiting)
            type.scalarKind() != null -> fields[field] = QueryFieldSchema(field, checkNotNull(type.scalarKind()), nullable)
            type.rawClass in visiting -> fields[field] =
                QueryFieldSchema(field, QueryValueKind.MAP, nullable, queryable = false)
            else -> {
                fields[field] = QueryFieldSchema(field, QueryValueKind.OBJECT, nullable, queryable = false)
                deriveProperties(type, path, fields, visiting)
            }
        }
    }

    private fun addCollection(
        type: JavaType,
        field: LogicalField,
        nullable: Boolean,
        fields: MutableMap<LogicalField, QueryFieldSchema>,
        visiting: MutableSet<Class<*>>
    ) {
        val content = requireNotNull(type.contentType) { "Query collection element type is required." }
        val scalar = content.scalarKind()
        if (scalar != null) {
            fields[field] = QueryFieldSchema(field, scalar, nullable, QueryCollectionKind.SCALAR, sortable = false)
            return
        }
        if (content.isMapLikeType || content.rawClass in visiting) {
            fields[field] = QueryFieldSchema(
                field,
                QueryValueKind.MAP,
                nullable,
                QueryCollectionKind.OBJECT,
                queryable = false,
                sortable = false,
                elementMatch = false,
                operators = emptySet(),
                fullText = false
            )
            return
        }
        fields[field] = QueryFieldSchema(
            field,
            QueryValueKind.OBJECT,
            nullable,
            QueryCollectionKind.OBJECT,
            queryable = true,
            sortable = false
        )
        deriveProperties(content, field.value, fields, visiting)
    }

    private fun beanProperties(type: JavaType): List<BeanPropertyDefinition> {
        val config = objectMapper.serializationConfig()
        val introspector = config.classIntrospectorInstance()
        return introspector.introspectForSerialization(type, introspector.introspectClassAnnotations(type))
            .findProperties()
            .filter(BeanPropertyDefinition::couldSerialize)
            .sortedBy(BeanPropertyDefinition::getName)
    }

    private fun BeanPropertyDefinition.isKotlinNullable(owner: Class<*>): Boolean {
        val member = accessor?.member ?: return !isRequired
        val property = owner.kotlin.memberProperties.firstOrNull { candidate ->
            when (member) {
                is Method -> candidate.javaGetter == member
                is Field -> candidate.javaField == member
                else -> false
            }
        }
        return property?.returnType?.isMarkedNullable ?: !isRequired
    }

    @Suppress("CyclomaticComplexMethod")
    private fun JavaType.scalarKind(): QueryValueKind? = when {
        rawClass == Boolean::class.java || rawClass == Boolean::class.javaPrimitiveType -> QueryValueKind.BOOLEAN
        rawClass == ByteArray::class.java -> QueryValueKind.BINARY
        isEnumType -> QueryValueKind.ENUM
        rawClass == String::class.java || rawClass == Char::class.java ||
            rawClass == Char::class.javaPrimitiveType || rawClass == java.util.UUID::class.java -> QueryValueKind.STRING
        rawClass == Byte::class.java || rawClass == Byte::class.javaPrimitiveType ||
            rawClass == Short::class.java || rawClass == Short::class.javaPrimitiveType ||
            rawClass == Int::class.java || rawClass == Int::class.javaPrimitiveType ||
            rawClass == Long::class.java || rawClass == Long::class.javaPrimitiveType ||
            rawClass == BigInteger::class.java -> QueryValueKind.INTEGER
        Number::class.java.isAssignableFrom(rawClass) || rawClass == Float::class.javaPrimitiveType ||
            rawClass == Double::class.javaPrimitiveType || rawClass == BigDecimal::class.java -> QueryValueKind.DECIMAL
        Date::class.java.isAssignableFrom(rawClass) || rawClass == Instant::class.java -> QueryValueKind.TIME
        isEncodedTemporalString() -> QueryValueKind.STRING
        else -> null
    }

    private fun JavaType.isEncodedTemporalString(): Boolean = rawClass != Instant::class.java &&
        (Temporal::class.java.isAssignableFrom(rawClass) || TemporalAccessor::class.java.isAssignableFrom(rawClass))

    private companion object {
        val STRING_PATTERN_OPERATORS = setOf(
            PredicateOperator.CONTAINS,
            PredicateOperator.STARTS_WITH,
            PredicateOperator.ENDS_WITH
        )
    }
}

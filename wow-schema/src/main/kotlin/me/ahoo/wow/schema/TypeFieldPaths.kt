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

package me.ahoo.wow.schema

import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomPropertyDefinition
import com.github.victools.jsonschema.generator.MemberScope
import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.schema.TypeFieldPaths.allFieldPaths
import me.ahoo.wow.schema.Types.isStdType
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.SnapshotRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.ser.bean.BeanSerializerBase
import tools.jackson.databind.ser.impl.UnknownSerializer
import tools.jackson.databind.ser.std.ReferenceTypeSerializer
import tools.jackson.databind.ser.std.StdContainerSerializer
import tools.jackson.databind.util.Converter
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Utility object to handle state field paths.
 */
object TypeFieldPaths {
    const val JOIN_DELIMITER = "."
    const val MAX_DEPTH = 5
    private val schemaGenerator by lazy {
        SchemaGeneratorBuilder().objectMapper(JsonSerializer).customizer { config ->
            config.with(Option.DEFINITIONS_FOR_ALL_OBJECTS)
            config.forFields().withCustomDefinitionProvider { scope, context ->
                scope.customSerializerDefinition(context)
            }
            config.forMethods().withCustomDefinitionProvider { scope, context ->
                scope.customSerializerDefinition(context)
            }
            config.forTypesInGeneral().withCustomDefinitionProvider { javaType, context ->
                javaType.erasedType.registeredSerializerDefinition(context)
            }
        }.build()
    }

    fun KClass<*>.allFieldPaths(
        parentName: String = "",
        fields: List<String> = emptyList(),
        maxDepth: Int = MAX_DEPTH
    ): Set<String> {
        val fieldPaths = linkedSetOf<String>()
        if (fields.isNotEmpty()) {
            fieldPaths.addAll(fields)
        }
        val rootSchema = schemaGenerator.generateSchema(java)
        rootSchema.collectFieldPaths(rootSchema, fieldPaths, parentName, 1, maxDepth, emptySet())
        return fieldPaths
    }

    private fun JsonNode.collectFieldPaths(
        rootSchema: JsonNode,
        fieldPaths: LinkedHashSet<String>,
        parentName: String,
        depth: Int,
        maxDepth: Int,
        resolvingReferences: Set<String>,
    ) {
        if (depth > maxDepth) {
            return
        }
        get("\$ref")?.asString()?.takeIf { it.startsWith("#/") && it !in resolvingReferences }?.let { reference ->
            rootSchema.at(reference.removePrefix("#")).collectFieldPaths(
                rootSchema,
                fieldPaths,
                parentName,
                depth,
                maxDepth,
                resolvingReferences + reference,
            )
        }
        listOf("allOf", "anyOf", "oneOf").forEach { composition ->
            get(composition)?.forEach { alternative ->
                alternative.collectFieldPaths(
                    rootSchema,
                    fieldPaths,
                    parentName,
                    depth,
                    maxDepth,
                    resolvingReferences,
                )
            }
        }
        get("properties")?.properties()?.forEach { (propertyName, propertySchema) ->
            if (propertyName.isLogicalFieldSegment() && propertySchema.get("writeOnly")?.asBoolean() != true) {
                val fullName = resolveFieldName(parentName, propertyName)
                fieldPaths.add(fullName)
                propertySchema.collectFieldPaths(rootSchema, fieldPaths, fullName, depth + 1, maxDepth, emptySet())
            }
        }
        get("items")?.collectFieldPaths(
            rootSchema,
            fieldPaths,
            parentName,
            depth,
            maxDepth,
            resolvingReferences,
        )
    }

    private fun resolveFieldName(parentName: String, fieldName: String): String =
        if (parentName.isBlank()) fieldName else "$parentName${JOIN_DELIMITER}$fieldName"

    private fun String.isLogicalFieldSegment(): Boolean =
        JOIN_DELIMITER !in this && runCatching { LogicalField(this) }.isSuccess

    private fun MemberScope<*, *>.customSerializerDefinition(
        context: SchemaGenerationContext,
    ): CustomPropertyDefinition? {
        val annotation = getAnnotationConsideringFieldAndGetterIfSupported(JsonSerialize::class.java)
        return annotation?.takeIf { it.definesWireShape() }?.let {
            CustomPropertyDefinition(context.generatorConfig.createObjectNode())
        }
    }

    private fun JsonSerialize.definesWireShape(): Boolean =
        listOf(contentUsing, keyUsing, converter, contentConverter, using).any {
            it != ValueSerializer.None::class.java && it != Converter.None::class.java
        }

    private fun Class<*>.registeredSerializerDefinition(context: SchemaGenerationContext): CustomDefinition? {
        if (isStdType()) {
            return null
        }
        val serializer = runCatching { JsonSerializer._serializationContext().findValueSerializer(this) }.getOrNull()
        return serializer?.takeUnless {
            it is BeanSerializerBase ||
                it is UnknownSerializer ||
                it is StdContainerSerializer<*> ||
                it is ReferenceTypeSerializer<*>
        }?.let {
            CustomDefinition(context.generatorConfig.createObjectNode())
        }
    }
}

object AggregatedFieldPaths {
    private val commandFieldPaths = ConcurrentHashMap<Class<*>, Set<String>>()

    fun KClass<*>.stateAggregatedFieldPaths(): Set<String> {
        return allFieldPaths(
            parentName = StateAggregateRecords.STATE,
            fields = listOf(
                "",
                MessageRecords.CONTEXT_NAME,
                MessageRecords.AGGREGATE_NAME,
                MessageRecords.AGGREGATE_ID,
                MessageRecords.TENANT_ID,
                MessageRecords.OWNER_ID,
                MessageRecords.SPACE_ID,
                MessageRecords.VERSION,
                StateAggregateRecords.EVENT_ID,
                StateAggregateRecords.FIRST_OPERATOR,
                StateAggregateRecords.OPERATOR,
                StateAggregateRecords.FIRST_EVENT_TIME,
                StateAggregateRecords.EVENT_TIME,
                StateAggregateRecords.TAGS,
                StateAggregateRecords.DELETED,
                StateAggregateRecords.STATE,
                SnapshotRecords.SNAPSHOT_TIME,
            )
        ).filterTo(linkedSetOf()) { field ->
            runCatching { LogicalField(field) }.isSuccess
        }
    }

    fun KClass<*>.commandAggregatedFieldPaths(): Set<String> = commandFieldPaths.computeIfAbsent(
        java
    ) { aggregateType ->
        aggregateType.aggregateMetadata<Any, Any>().state.aggregateType.kotlin.stateAggregatedFieldPaths()
    }
}

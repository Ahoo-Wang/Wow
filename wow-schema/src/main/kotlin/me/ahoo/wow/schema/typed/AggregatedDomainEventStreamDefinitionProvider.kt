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

package me.ahoo.wow.schema.typed

import com.fasterxml.classmate.ResolvedType
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.SchemaKeyword
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.schema.JsonSchema.Companion.asCustomDefinition
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema
import me.ahoo.wow.schema.JsonSchema.Companion.toPropertyName
import me.ahoo.wow.schema.WowSchemaLoader
import me.ahoo.wow.serialization.MessageRecords

object AggregatedDomainEventStreamDefinitionProvider : CustomDefinitionProviderV2 {
    private val type: Class<*> = AggregatedDomainEventStream::class.java

    override fun provideCustomSchemaDefinition(
        javaType: ResolvedType,
        context: SchemaGenerationContext
    ): CustomDefinition? {
        if (!javaType.isInstanceOf(type)) {
            return null
        }
        val rootNode = WowSchemaLoader.load(DomainEventStream::class.java)
        if (javaType.typeBindings.isEmpty) {
            return CustomDefinition(rootNode)
        }
        val commandAggregateType = javaType.typeBindings.getBoundType(0).erasedType
        val aggregateMetadata = commandAggregateType.aggregateMetadata<Any, Any>()
        val eventTypes = aggregateMetadata.state.sourcingFunctionRegistry.keys.sortedBy { it.name }
        if (eventTypes.isEmpty()) {
            return CustomDefinition(rootNode)
        }
        val rootSchema = rootNode.asJsonSchema()
        val rootPropertiesNode = rootSchema.requiredGetProperties()
        val itemsNode =
            rootPropertiesNode[DomainEventStream::body.name][SchemaKeyword.TAG_ITEMS.toPropertyName()] as ObjectNode
        val itemsSchema = itemsNode.asJsonSchema()
        val bodyTypeNode = itemsSchema.requiredGetProperties()[MessageRecords.BODY_TYPE] as ObjectNode
        val bodyTypeEnumNode = bodyTypeNode.putArray(SchemaKeyword.TAG_ENUM.toPropertyName())
        val bodyNode = itemsSchema.requiredGetProperties()[MessageRecords.BODY] as ObjectNode
        val bodyAnyOfNode = bodyNode.putArray(SchemaKeyword.TAG_ANYOF.toPropertyName())
        eventTypes.forEach { eventType ->
            bodyTypeEnumNode.add(eventType.name)
            val eventNode = createEventTypeDefinition(eventType, context)
            bodyAnyOfNode.add(eventNode)
        }

        return rootSchema.asCustomDefinition()
    }

    private fun createEventTypeDefinition(eventType: Class<*>, context: SchemaGenerationContext): ObjectNode {
        if (context.generatorConfig.shouldCreateDefinitionsForAllObjects()) {
            return context.createDefinitionReference(context.typeContext.resolve(eventType))
        }
        return context.createStandardDefinition(context.typeContext.resolve(eventType), this)
    }
}

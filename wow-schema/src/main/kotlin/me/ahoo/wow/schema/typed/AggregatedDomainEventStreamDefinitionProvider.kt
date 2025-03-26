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
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.SchemaKeyword
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.annotation.toEventMetadata
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.schema.JsonSchema.Companion.asCustomDefinition
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema
import me.ahoo.wow.schema.JsonSchema.Companion.toPropertyName
import me.ahoo.wow.schema.WowSchemaLoader
import me.ahoo.wow.serialization.MessageRecords

object AggregatedDomainEventStreamDefinitionProvider : CustomDefinitionProviderV2 {
    private const val DOMAIN_EVENT_STREAM_BODY_RESOURCE_NAME = "DomainEventStreamBody"
    private val type: Class<*> = AggregatedDomainEventStream::class.java

    override fun provideCustomSchemaDefinition(
        javaType: ResolvedType,
        context: SchemaGenerationContext
    ): CustomDefinition? {
        if (!javaType.isInstanceOf(type)) {
            return null
        }

        if (javaType.typeBindings.isEmpty) {
            return domainEventStreamNode()
        }
        val schemaVersion = context.generatorConfig.schemaVersion
        val commandAggregateType = javaType.typeBindings.getBoundType(0).erasedType
        val aggregateMetadata = commandAggregateType.aggregateMetadata<Any, Any>()
        val eventTypes = aggregateMetadata.state.sourcingFunctionRegistry.keys.sortedBy { it.name }
        if (eventTypes.isEmpty()) {
            return domainEventStreamNode()
        }
        val rootSchema = WowSchemaLoader.load(type).asJsonSchema(schemaVersion)
        val rootPropertiesNode = rootSchema.requiredGetProperties()
        val rootPropertiesBodyNode = rootPropertiesNode[DomainEventStream::body.name] as ObjectNode
        val itemsNode = rootPropertiesBodyNode[SchemaKeyword.TAG_ITEMS.toPropertyName()] as ObjectNode
        val itemsAnyOfNode = itemsNode[SchemaKeyword.TAG_ANYOF.toPropertyName(schemaVersion)] as ArrayNode
        val eventBodyNodeTemplate = WowSchemaLoader.load(DOMAIN_EVENT_STREAM_BODY_RESOURCE_NAME)
        eventTypes.forEach { eventType ->
            val eventMetadata = eventType.toEventMetadata()
            val eventBodySchema = eventBodyNodeTemplate.deepCopy().asJsonSchema(schemaVersion)
            eventBodySchema.actual.put(SchemaKeyword.TAG_TITLE.toPropertyName(schemaVersion), eventMetadata.name)
            val eventBodyPropertiesNode = eventBodySchema.requiredGetProperties()
            val eventBodyNameNode = eventBodyPropertiesNode[MessageRecords.NAME] as ObjectNode
            eventBodyNameNode.put(SchemaKeyword.TAG_CONST.toPropertyName(), eventMetadata.name)
            val eventBodyTypeNode = eventBodyPropertiesNode[MessageRecords.BODY_TYPE] as ObjectNode
            eventBodyTypeNode.put(SchemaKeyword.TAG_CONST.toPropertyName(), eventType.name)
            val eventNode = createEventTypeDefinition(eventType, context)
            eventBodyPropertiesNode.set<ObjectNode>(MessageRecords.BODY, eventNode)
            itemsAnyOfNode.add(eventBodySchema.actual)
        }

        return rootSchema.asCustomDefinition()
    }

    private fun createEventTypeDefinition(eventType: Class<*>, context: SchemaGenerationContext): ObjectNode {
        if (context.generatorConfig.shouldCreateDefinitionsForAllObjects()) {
            return context.createDefinitionReference(context.typeContext.resolve(eventType))
        }
        return context.createStandardDefinition(context.typeContext.resolve(eventType), this)
    }

    private fun domainEventStreamNode(): CustomDefinition {
        return CustomDefinition(WowSchemaLoader.load(DomainEventStream::class.java))
    }
}

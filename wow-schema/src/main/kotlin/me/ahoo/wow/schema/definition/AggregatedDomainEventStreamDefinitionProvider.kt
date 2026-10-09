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

package me.ahoo.wow.schema.definition

import com.fasterxml.classmate.ResolvedType
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.SchemaKeyword
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.annotation.toEventMetadata
import me.ahoo.wow.event.metadata.EventMetadata
import me.ahoo.wow.infra.TypeNameMapper.toType
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.schema.WowSchemaLoader
import me.ahoo.wow.schema.typed.AggregatedDomainEventStream
import me.ahoo.wow.serialization.MessageRecords
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

internal object AggregatedDomainEventStreamDefinitionProvider : CustomDefinitionProviderV2 {
    private const val DOMAIN_EVENT_STREAM_BODY_RESOURCE_NAME = "DomainEventStreamBody"
    private val type: Class<*> = AggregatedDomainEventStream::class.java

    override fun provideCustomSchemaDefinition(
        javaType: ResolvedType,
        context: SchemaGenerationContext
    ): CustomDefinition? {
        if (!javaType.isInstanceOf(type)) {
            return null
        }
        val commandAggregateType = javaType.typeBindings.getBoundType(0)?.erasedType
        if (commandAggregateType == null || commandAggregateType == Any::class.java) {
            return domainEventStreamNode()
        }
        val eventMetadataSet = resolveEvents(commandAggregateType)
        if (eventMetadataSet.isEmpty()) {
            return domainEventStreamNode()
        }
        val propertiesKey = context.getKeyword(SchemaKeyword.TAG_PROPERTIES)
        val titleKey = context.getKeyword(SchemaKeyword.TAG_TITLE)
        val constKey = context.getKeyword(SchemaKeyword.TAG_CONST)
        val rootSchema = WowSchemaLoader.load(type)
        val rootBodyNode = rootSchema[propertiesKey][DomainEventStream::body.name] as ObjectNode
        val itemsNode = rootBodyNode[context.getKeyword(SchemaKeyword.TAG_ITEMS)] as ObjectNode
        val itemsAnyOfNode = itemsNode[context.getKeyword(SchemaKeyword.TAG_ANYOF)] as ArrayNode
        val eventBodyNodeTemplate = WowSchemaLoader.load(DOMAIN_EVENT_STREAM_BODY_RESOURCE_NAME)
        eventMetadataSet.forEach { eventMetadata ->
            val eventBodySchema = eventBodyNodeTemplate.deepCopy()
            val title = eventMetadata.eventType.getAnnotation(Summary::class.java)?.value ?: eventMetadata.name
            eventBodySchema.put(titleKey, title)
            val eventBodyPropertiesNode = eventBodySchema[propertiesKey] as ObjectNode
            val eventBodyNameNode = eventBodyPropertiesNode[MessageRecords.NAME] as ObjectNode
            eventBodyNameNode.put(constKey, eventMetadata.name)
            val eventBodyTypeNode = eventBodyPropertiesNode[MessageRecords.BODY_TYPE] as ObjectNode
            eventBodyTypeNode.put(constKey, eventMetadata.eventType.name)
            val eventNode = createEventTypeDefinition(eventMetadata, context)
            eventBodyPropertiesNode.set(MessageRecords.BODY, eventNode)
            itemsAnyOfNode.add(eventBodySchema)
        }

        return CustomDefinition(rootSchema)
    }

    private fun createEventTypeDefinition(
        eventMetadata: EventMetadata<*>,
        context: SchemaGenerationContext
    ): ObjectNode {
        if (context.generatorConfig.shouldCreateDefinitionsForAllObjects()) {
            return context.createDefinitionReference(context.typeContext.resolve(eventMetadata.eventType))
        }
        return context.createStandardDefinition(context.typeContext.resolve(eventMetadata.eventType), this)
    }

    private fun domainEventStreamNode(): CustomDefinition {
        return CustomDefinition(WowSchemaLoader.load(DomainEventStream::class.java))
    }

    private fun resolveEvents(commandAggregateType: Class<*>): List<EventMetadata<*>> {
        val aggregateMetadata = commandAggregateType.aggregateMetadata<Any, Any>()
        val eventTypes = aggregateMetadata.state.sourcingFunctionRegistry.keys
        val metadataEventTypes = MetadataSearcher.getAggregate(aggregateMetadata.namedAggregate)?.events?.map {
            it.toType<Any>()
        } ?: emptyList()
        val mergedEventTypes = eventTypes + metadataEventTypes
        return mergedEventTypes.map {
            it.toEventMetadata()
        }.sortedBy { it.eventType.name }
    }
}

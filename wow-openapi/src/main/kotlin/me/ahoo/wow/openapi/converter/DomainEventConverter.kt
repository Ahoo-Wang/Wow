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

package me.ahoo.wow.openapi.converter

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemaName
import me.ahoo.wow.openapi.converter.BoundedContextSchemaNameConverter.Companion.getRawClass
import me.ahoo.wow.serialization.MessageRecords

/**
 * DomainEvent Converter
 * @see me.ahoo.wow.serialization.event.DomainEventJsonSerializer
 * @see me.ahoo.wow.serialization.event.EventStreamJsonSerializer
 */
class DomainEventConverter : ModelConverter {
    companion object {
        private val wrapTypes: Set<String> = setOf(
            DomainEventStream::class.java.toSchemaName()!!,
            StateEvent::class.java.toSchemaName()!!
        )
    }

    override fun resolve(
        type: AnnotatedType,
        context: ModelConverterContext,
        chain: Iterator<ModelConverter>
    ): Schema<*>? {
        if (!chain.hasNext()) {
            return null
        }
        val isDomainEvent = isDomainEvent(type)
        val isWrapped = (type.parent?.name ?: "") in wrapTypes
        if (isDomainEvent && isWrapped) {
            type.name = "wow.WrappedDomainEvent"
        }
        val resolvedSchema = chain.next().resolve(type, context, chain) ?: return null
        if (!isDomainEvent) {
            return resolvedSchema
        }
        if (isWrapped && resolvedSchema.properties != null) {
            resolvedSchema.properties["bodyType"] = StringSchema()
            resolvedSchema.properties.remove("last")
            resolvedSchema.properties.remove(DomainEvent<*>::header.name)
            resolvedSchema.properties.remove(MessageRecords.AGGREGATE_ID)
            resolvedSchema.properties.remove(DomainEvent<*>::sequence.name)
            resolvedSchema.properties.remove("readOnly")
            resolvedSchema.properties.remove(DomainEvent<*>::createTime.name)
            resolvedSchema.properties.remove(DomainEvent<*>::contextName.name)
            resolvedSchema.properties.remove(DomainEvent<*>::commandId.name)
            resolvedSchema.properties.remove(DomainEvent<*>::aggregateName.name)
            resolvedSchema.properties.remove(DomainEvent<*>::version.name)
            resolvedSchema.properties.remove("initialVersion")
            resolvedSchema.properties.remove(DomainEvent<*>::initialized.name)
        }

        return resolvedSchema
    }

    private fun isDomainEvent(type: AnnotatedType): Boolean {
        val rawClass = type.getRawClass() ?: return false
        return rawClass == DomainEvent::class.java
    }
}

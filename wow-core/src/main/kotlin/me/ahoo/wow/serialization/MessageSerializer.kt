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

package me.ahoo.wow.serialization

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.serialization.event.JsonDomainEvent

abstract class MessageSerializer<M : Message<*, *>>(messageType: Class<M>) : StdSerializer<M>(messageType) {

    override fun serialize(value: M, generator: JsonGenerator, provider: SerializerProvider) {
        generator.writeStartObject()
        generator.writeStringField(MessageRecords.ID, value.id)
        if (value is NamedBoundedContext) {
            generator.writeStringField(MessageRecords.CONTEXT_NAME, value.contextName)
        }
        if (value is NamedAggregate) {
            generator.writeStringField(MessageRecords.AGGREGATE_NAME, value.aggregateName)
        }
        if (value is Named) {
            generator.writeStringField(MessageRecords.NAME, value.name)
        }
        writeHeader(generator, value.header)
        writeExtendedInfo(generator, value)
        writeBodyType(generator, value)
        writeBody(generator, value)
        generator.writeNumberField(MessageRecords.CREATE_TIME, value.createTime)
        generator.writeEndObject()
    }

    open fun writeExtendedInfo(generator: JsonGenerator, value: M) {
    }

    open fun writeHeader(generator: JsonGenerator, value: Header) {
        generator.writePOJOField(MessageRecords.HEADER, value)
    }

    open fun writeBodyType(generator: JsonGenerator, value: M) {
        if (value is JsonDomainEvent) {
            generator.writeStringField(MessageRecords.BODY_TYPE, value.bodyType)
            return
        }
        generator.writeStringField(MessageRecords.BODY_TYPE, value.body!!.javaClass.name)
    }

    open fun writeBody(generator: JsonGenerator, value: M) {
        generator.writePOJOField(MessageRecords.BODY, value.body)
    }
}

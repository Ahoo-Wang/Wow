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

package me.ahoo.wow.api.query

import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.ser.std.StdSerializer

object LogicalFieldJsonSerializer : StdSerializer<LogicalField>(LogicalField::class.java) {
    override fun serialize(
        value: LogicalField,
        generator: JsonGenerator,
        provider: SerializationContext,
    ) {
        if (value.type == null) {
            generator.writeString(value.name)
            return
        }
        generator.writeStartObject()
        generator.writeStringProperty("name", value.name)
        generator.writePOJOProperty("type", value.type)
        generator.writeEndObject()
    }
}

object LogicalFieldJsonDeserializer : StdDeserializer<LogicalField>(LogicalField::class.java) {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): LogicalField {
        val node = context.readTree(parser)
        if (node.isString) return LogicalField(node.asString())
        if (!node.isObject) {
            return context.reportInputMismatch(
                LogicalField::class.java,
                "LogicalField must be a string or object.",
            )
        }
        val unknownProperties = node.properties().map { it.key }
            .filterNot { it == "name" || it == "type" }
        if (unknownProperties.isNotEmpty()) {
            return context.reportInputMismatch(
                LogicalField::class.java,
                "LogicalField object contains unknown properties $unknownProperties.",
            )
        }
        val name = node["name"]?.takeIf { it.isString && !it.isMissingNode }?.asString()
            ?: return context.reportInputMismatch(
                LogicalField::class.java,
                "LogicalField object requires string property [name].",
            )
        val typeNode = node["type"]
        if (typeNode?.isNull == true) {
            return context.reportInputMismatch(
                LogicalField::class.java,
                "LogicalField object property [type] cannot be null.",
            )
        }
        val type = typeNode?.let { context.readTreeAsValue(it, FieldType::class.java) }
        return LogicalField(name, type)
    }
}

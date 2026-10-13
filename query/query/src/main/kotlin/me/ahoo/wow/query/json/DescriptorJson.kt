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

package me.ahoo.wow.query.json

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import me.ahoo.wow.query.schema.NumericFormat
import me.ahoo.wow.query.schema.QueryDeprecation
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QuerySemanticType
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Reference
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.schema.TimeSpan
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.jsontype.NamedType
import tools.jackson.databind.module.SimpleModule

/*
 * The JSON of the capability descriptor's vocabulary (`me.ahoo.wow.query.descriptor`), as 9.x `wow-api` wrote it:
 * a model and a value type are bare strings, a semantic type is an object discriminated by `type` (one this version
 * does not know reads as `QuerySemanticType.Unknown`), and absent optional members are omitted.
 */

/** The semantic types under their `type` names. */
internal val semanticSubtypes: List<NamedType> = listOf(
    NamedType(Temporal.Date::class.java, "TEMPORAL_DATE"),
    NamedType(Temporal.Epoch::class.java, "TEMPORAL_EPOCH"),
    NamedType(Temporal.Formatted::class.java, "TEMPORAL_FORMATTED"),
    NamedType(NumericFormat.Decimal::class.java, "DECIMAL"),
    NamedType(NumericFormat.Money::class.java, "MONEY"),
    NamedType(TimeSpan::class.java, "DURATION"),
    NamedType(Reference::class.java, "REFERENCE"),
)

internal fun SimpleModule.registerDescriptorJson() {
    setMixInAnnotation(QueryModel::class.java, StringValueMixin::class.java)
    setMixInAnnotation(QueryValueType::class.java, StringValueMixin::class.java)
    addDeserializer(QueryModel::class.java, StringValueDeserializer(QueryModel::class.java, ::QueryModel))
    addDeserializer(QueryValueType::class.java, StringValueDeserializer(QueryValueType::class.java, ::QueryValueType))
    setMixInAnnotation(QuerySemanticType::class.java, SemanticTypeMixin::class.java)
    setMixInAnnotation(QuerySemanticType.Unknown::class.java, UnknownSemanticTypeMixin::class.java)
    setMixInAnnotation(NumericFormat.Money::class.java, NonNullMixin::class.java)
    setMixInAnnotation(Reference::class.java, NonNullMixin::class.java)
    setMixInAnnotation(QueryDeprecation::class.java, NonNullMixin::class.java)
    registerSubtypes(*semanticSubtypes.toTypedArray())
}

internal interface StringValueMixin {
    @get:JsonValue
    val value: String
}

private class StringValueDeserializer<T : Any>(type: Class<T>, private val create: (String) -> T) :
    StdDeserializer<T>(type) {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): T = create(parser.valueAsString)
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = QuerySemanticType.Unknown::class)
internal interface SemanticTypeMixin

@JsonIgnoreProperties(ignoreUnknown = true)
internal interface UnknownSemanticTypeMixin

@JsonInclude(JsonInclude.Include.NON_NULL)
internal interface NonNullMixin

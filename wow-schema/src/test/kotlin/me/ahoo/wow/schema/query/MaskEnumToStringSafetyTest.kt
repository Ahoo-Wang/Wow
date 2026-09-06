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

package me.ahoo.wow.schema.query

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.OnSourcing
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode

class MaskEnumToStringSafetyTest {
    private fun load(type: Class<*>, model: QueryModel = QueryModel.SNAPSHOT) =
        JsonQuerySchemaSource(typeResolver = { type }).load(
            QuerySchemaContext(MaterializedNamedAggregate("test", "mask-enum-values"), model),
        ).single().block()!!

    @TestFactory
    fun `enum toString values cannot bypass masked member validation`() = listOf(
        Triple(RawEnum.A, RawEnum::class.java, ""),
        Triple(RawState(RawEnum.A), RawState::class.java, "/value"),
        Triple(RawList(listOf(RawEnum.A)), RawList::class.java, "/values/0"),
        Triple(ConstantEnum.A, ConstantEnum::class.java, ""),
        Triple(TextualNumericState(NumericEnum.A), TextualNumericState::class.java, "/value"),
        Triple(NumericFirst(RawEnum.A, RawEnum.A), NumericFirst::class.java, "/textual"),
        Triple(TextualFirst(RawEnum.A, RawEnum.A), TextualFirst::class.java, "/textual"),
        Triple(HiddenThenObject(PlainEnum.A, PlainEnum.A), HiddenThenObject::class.java, "/value/secret"),
        Triple(
            NumericListsFirst(listOf(RawEnum.A), listOf(RawEnum.A)),
            NumericListsFirst::class.java,
            "/textual/0",
        ),
    ).map { (value, type, path) ->
        DynamicTest.dynamicTest(type.simpleName) {
            JsonSerializer.valueToTree<JsonNode>(value).at(path).stringValue().assert().isEqualTo("customer-secret")
            assertThrows<QuerySchemaConflictException> { load(type) }
        }
    }

    @TestFactory
    fun `fixed enum names and numeric shapes remain supported`() = listOf(
        Triple(PlainEnum.A, PlainEnum::class.java, "\"A\""),
        Triple(NamedEnum.A, NamedEnum::class.java, "\"wire-a\""),
        Triple(UnmaskedEnum.A, UnmaskedEnum::class.java, "\"public-value\""),
        Triple(NumericEnum.A, NumericEnum::class.java, "0"),
        Triple(NumericState(RawEnum.A), NumericState::class.java, "{\"value\":0}"),
        Triple(NumericList(listOf(RawEnum.A)), NumericList::class.java, "{\"values\":[0]}"),
        Triple(
            UnmaskedObjectState(UnmaskedEnum.A),
            UnmaskedObjectState::class.java,
            "{\"value\":{\"value\":\"public-value\"}}"
        ),
    ).map { (value, type, json) ->
        DynamicTest.dynamicTest(type.simpleName) {
            JsonSerializer.valueToTree<JsonNode>(value).assert().isEqualTo(JsonSerializer.readTree(json))
            load(type)
        }
    }

    @Test
    fun `event stream rejects masked enum toString payload roots`() {
        assertThrows<QuerySchemaConflictException> { load(RawEnumAggregate::class.java, QueryModel.EVENT_STREAM) }
    }

    @Test
    fun `event stream accepts numeric enum payload roots`() {
        load(NumericEnumAggregate::class.java, QueryModel.EVENT_STREAM)
    }

    enum class RawEnum(@field:Mask @field:JsonIgnore val secret: String) {
        A("customer-secret");
        override fun toString(): String = secret
    }
    enum class ConstantEnum(@field:Mask val secret: String) {
        A("customer-secret") {
            override fun toString(): String = secret
        }
    }
    enum class PlainEnum(@field:Mask val secret: String) { A("customer-secret") }
    enum class NamedEnum(@field:Mask val secret: String) {
        @JsonProperty("wire-a")
        A("customer-secret");
        override fun toString(): String = secret
    }
    enum class UnmaskedEnum(val value: String) {
        A("public-value");
        override fun toString(): String = value
    }

    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    enum class NumericEnum(@field:Mask val secret: String) {
        A("customer-secret");
        override fun toString(): String = secret
    }
    data class RawState(val value: RawEnum)
    data class RawList(val values: List<RawEnum>)
    data class NumericState(@field:JsonFormat(shape = JsonFormat.Shape.NUMBER) val value: RawEnum)
    data class NumericList(@get:JsonFormat(shape = JsonFormat.Shape.NUMBER) val values: List<RawEnum>)
    data class TextualNumericState(@get:JsonFormat(shape = JsonFormat.Shape.STRING) val value: NumericEnum)
    data class NumericFirst(
        @field:JsonFormat(shape = JsonFormat.Shape.NUMBER) val numeric: RawEnum,
        val textual: RawEnum,
    )
    data class TextualFirst(
        val textual: RawEnum,
        @get:JsonFormat(shape = JsonFormat.Shape.NUMBER) val numeric: RawEnum,
    )

    data class NumericListsFirst(
        @field:JsonFormat(shape = JsonFormat.Shape.NUMBER) val numeric: List<RawEnum>,
        val textual: List<RawEnum>,
    )
    data class UnmaskedObjectState(@get:JsonFormat(shape = JsonFormat.Shape.OBJECT) val value: UnmaskedEnum)
    data class HiddenThenObject(
        @field:Schema(hidden = true) val hidden: PlainEnum,
        @field:JsonFormat(shape = JsonFormat.Shape.OBJECT) val value: PlainEnum,
    )

    @AggregateRoot
    class RawEnumAggregate(val id: String) {
        var value: RawEnum? = null

        @OnSourcing
        fun onEvent(event: RawEnum) {
            value = event
        }
    }

    @AggregateRoot
    class NumericEnumAggregate(val id: String) {
        var value: NumericEnum? = null

        @OnSourcing
        fun onEvent(event: NumericEnum) {
            value = event
        }
    }
}

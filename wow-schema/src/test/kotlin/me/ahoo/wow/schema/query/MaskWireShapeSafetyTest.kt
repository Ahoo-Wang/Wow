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
import com.fasterxml.jackson.annotation.JsonTypeId
import com.fasterxml.jackson.annotation.JsonTypeInfo
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
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.ser.bean.BeanSerializerBase
import tools.jackson.databind.ser.jdk.EnumSerializer

class MaskWireShapeSafetyTest {
    private fun load(type: Class<*>, model: QueryModel = QueryModel.SNAPSHOT) =
        JsonQuerySchemaSource(typeResolver = { type }).load(
            QuerySchemaContext(MaterializedNamedAggregate("test", "mask-wire-shape"), model),
        ).single().block()!!

    @TestFactory
    fun `allOf preserves unsafe parent context`() = listOf(
        HiddenAllOf(Branch()),
        OpaqueAllOf(Branch()),
        HiddenClassAllOf(ClassBranch()),
    ).map { value ->
        DynamicTest.dynamicTest(value.javaClass.simpleName) {
            val tree = JsonSerializer.valueToTree<JsonNode>(value)
            val raw = if (value is OpaqueAllOf) tree.path("value") else tree.path("value").path("secret")
            raw.stringValue().assert().isEqualTo("raw")
            assertThrows<QuerySchemaConflictException> { load(value.javaClass) }
        }
    }

    @TestFactory
    fun `object enums expose masked bean properties and must fail closed`() = listOf(
        ObjectEnum.A to "/secret",
        ObjectEnumState(ObjectEnum.A) to "/value/secret",
    ).map { (value, path) ->
        DynamicTest.dynamicTest(value.javaClass.simpleName) {
            (JsonSerializer._serializationContext().findValueSerializer(ObjectEnum::class.java) is BeanSerializerBase)
                .assert().isTrue()
            JsonSerializer.valueToTree<JsonNode>(value).at(path).stringValue().assert().isEqualTo("raw")
            assertThrows<QuerySchemaConflictException> { load(value.javaClass) }
        }
    }

    @TestFactory
    fun `masked type ids are emitted under the discriminator instead of the property`() = listOf(
        TypeIdValue("raw") to "",
        TypeIdState(TypeIdValue("raw")) to "/value",
        GetterTypeIdValue("raw") to "",
    ).map { (value, path) ->
        DynamicTest.dynamicTest(value.javaClass.simpleName) {
            val tree = JsonSerializer.valueToTree<JsonNode>(value).at(path)
            tree.path("kind").stringValue().assert().isEqualTo("raw")
            tree.has("secret").assert().isFalse()
            assertThrows<QuerySchemaConflictException> { load(value.javaClass) }
        }
    }

    @TestFactory
    fun `type ids inspect members hidden by ordinary bean and enum serialization`() = listOf(
        IgnoredTokenTypeId(IgnoredToken("customer-secret")),
        EnumTokenTypeId(EnumToken.A),
    ).map { value ->
        DynamicTest.dynamicTest(value.javaClass.simpleName) {
            val tree = JsonSerializer.valueToTree<JsonNode>(value)
            tree.path("kind").stringValue().assert().isEqualTo("customer-secret")
            tree.has("token").assert().isFalse()
            assertThrows<QuerySchemaConflictException> { load(value.javaClass) }
        }
    }

    @Test
    fun `ordinary ignored members and unmasked type id values remain supported`() {
        JsonSerializer.valueToTree<JsonNode>(IgnoredToken("customer-secret")).has("secret").assert().isFalse()
        JsonSerializer.valueToTree<JsonNode>(EnumToken.A).stringValue().assert().isEqualTo("wire-a")
        JsonSerializer.valueToTree<JsonNode>(UnmaskedEnumTypeId(UnmaskedObjectEnum.A))
            .path("kind").stringValue().assert().isEqualTo("A")
        load(IgnoredToken::class.java)
        load(EnumToken::class.java)
        load(UnmaskedEnumTypeId::class.java)
    }

    @TestFactory
    fun `event stream payload validation rejects unsafe wire shapes`() = listOf(
        AllOfAggregate::class.java,
        ObjectEnumAggregate::class.java,
        TypeIdAggregate::class.java,
    ).map { type ->
        DynamicTest.dynamicTest(type.simpleName) {
            assertThrows<QuerySchemaConflictException> { load(type, QueryModel.EVENT_STREAM) }
        }
    }

    @Test
    fun `ordinary enum names and unmasked object enums remain supported`() {
        (JsonSerializer._serializationContext().findValueSerializer(OrdinaryEnum::class.java) is EnumSerializer)
            .assert().isTrue()
        JsonSerializer.valueToTree<JsonNode>(OrdinaryEnum.A).stringValue().assert().isEqualTo("wire-a")
        JsonSerializer.valueToTree<JsonNode>(UnmaskedObjectEnum.A)
            .path("secret").stringValue().assert().isEqualTo("raw")
        load(OrdinaryEnum::class.java)
        load(UnmaskedObjectEnum::class.java)
        load(UnmaskedTypeId("raw")::class.java)
    }

    open class Base
    class Branch(@field:Mask val secret: String = "raw") : Base()
    data class HiddenAllOf(@field:Schema(hidden = true, allOf = [Branch::class]) val value: Base)
    data class OpaqueAllOf(
        @field:Schema(allOf = [Branch::class])
        @get:JsonSerialize(using = MaskMaterializationSafetyTest.OpaqueSerializer::class) val value: Base,
    )

    @Schema(allOf = [ClassBranch::class])
    open class ClassBase
    class ClassBranch(@field:Mask val secret: String = "raw") : ClassBase()
    data class HiddenClassAllOf(@field:Schema(hidden = true) val value: ClassBase)

    @JsonFormat(shape = JsonFormat.Shape.OBJECT)
    enum class ObjectEnum(@field:Mask val secret: String) { A("raw") }
    data class ObjectEnumState(val value: ObjectEnum)

    @JsonFormat(shape = JsonFormat.Shape.OBJECT)
    enum class UnmaskedObjectEnum(val secret: String) { A("raw") }
    enum class OrdinaryEnum(@field:Mask val secret: String) {
        @JsonProperty("wire-a")
        A("raw")
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    data class TypeIdValue(@field:Mask @field:JsonTypeId val secret: String)

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    data class GetterTypeIdValue(@get:Mask @get:JsonTypeId val secret: String)
    data class TypeIdState(val value: TypeIdValue)

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    data class UnmaskedTypeId(@field:JsonTypeId val secret: String)

    class IgnoredToken(@field:Mask @field:JsonIgnore val secret: String) {
        override fun toString(): String = secret
    }
    enum class EnumToken(@field:Mask val secret: String) {
        @JsonProperty("wire-a")
        A("customer-secret");
        override fun toString(): String = secret
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    data class IgnoredTokenTypeId(@field:JsonTypeId val token: IgnoredToken)

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    data class EnumTokenTypeId(@field:JsonTypeId val token: EnumToken)

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    data class UnmaskedEnumTypeId(@field:JsonTypeId val token: UnmaskedObjectEnum)

    @AggregateRoot
    class AllOfAggregate(val id: String) {
        var value: HiddenAllOf? = null

        @OnSourcing
        fun onEvent(event: HiddenAllOf) {
            value = event
        }
    }

    @AggregateRoot
    class ObjectEnumAggregate(val id: String) {
        var value: ObjectEnum? = null

        @OnSourcing
        fun onEvent(event: ObjectEnum) {
            value = event
        }
    }

    @AggregateRoot
    class TypeIdAggregate(val id: String) {
        var value: TypeIdValue? = null

        @OnSourcing
        fun onEvent(event: TypeIdValue) {
            value = event
        }
    }
}

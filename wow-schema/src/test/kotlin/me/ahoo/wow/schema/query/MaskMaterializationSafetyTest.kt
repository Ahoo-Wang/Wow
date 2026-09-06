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

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.annotation.JsonDeserialize
import tools.jackson.databind.annotation.JsonPOJOBuilder
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.ser.std.StdSerializer
import tools.jackson.databind.util.StdConverter

class MaskMaterializationSafetyTest {
    private fun load(type: Class<*>) = JsonQuerySchemaSource(typeResolver = { type }).load(
        QuerySchemaContext(MaterializedNamedAggregate("test", "mask-safety"), QueryModel.SNAPSHOT),
    ).single().block()!!

    @TestFactory
    fun `unsafe declarations must fail closed`() = listOf(
        HiddenOneOf::class.java,
        HiddenAnyOf::class.java,
        OpaqueOneOf::class.java,
        HiddenSubtype::class.java,
        RenamedIgnored::class.java,
        DocumentationWriteOnly::class.java,
        DocumentationWriteOnlyParent::class.java,
        PropertyDecoder::class.java,
        PropertyConverter::class.java,
        ContentDecoder::class.java,
        ContentConverter::class.java,
        TypeDecoder::class.java,
        TypeConverter::class.java,
        ContainerDecoder::class.java,
        ComputedOpaque::class.java,
        IgnoredNestedOpaque::class.java,
        OpaqueSubtype::class.java,
        HiddenClassAlternative::class.java,
        ConstructorDecoder::class.java,
        SetterDecoder::class.java,
        MaskedKeys::class.java,
        CustomMaskedKeys::class.java,
        PrivateOpaque::class.java,
        PrivateOpaqueContainer::class.java,
        IgnoredOpaque::class.java,
        PropertyPrivateOpaque::class.java,
        InheritedPrivateOpaque::class.java,
    ).map { type ->
        DynamicTest.dynamicTest(type.simpleName) {
            assertThrows<QuerySchemaConflictException> { load(type) }
        }
    }

    @Test
    fun `renamed ignore and documentation write only still serialize raw values`() {
        JsonSerializer.valueToTree<tools.jackson.databind.JsonNode>(
            RenamedIgnored("raw")
        ).path("wire_secret").stringValue().assert().isEqualTo("raw")
        JsonSerializer.valueToTree<tools.jackson.databind.JsonNode>(
            DocumentationWriteOnly("raw")
        ).path("secret").stringValue().assert().isEqualTo("raw")
    }

    @Test
    fun `custom decoder restores secret from masked input`() {
        JsonSerializer.treeToValue(JsonSerializer.readTree("""{"secret":"*****"}"""), PropertyDecoder::class.java)
            .secret.assert().isEqualTo("raw")
    }

    @Test
    fun `builder retains a compiled masked value during typed materialization`() {
        val declaration = load(BuilderValue::class.java)
        val rule = (declaration.fields.getValue(QueryField("state.secret")).maskRule as DeclarationValue.Set).value
        val masked = rule.compiled.mask("raw")
        val tree = JsonSerializer.createObjectNode().put("secret", masked)
        val materialized = JsonSerializer.treeToValue(tree, BuilderValue::class.java)
        materialized.secret.assert().isEqualTo(masked)
        JsonSerializer.valueToTree<tools.jackson.databind.JsonNode>(
            materialized
        ).path("secret").stringValue().assert().isEqualTo(masked)
    }

    @Test
    fun `unmasked opaque models and ordinary ignored members remain supported`() {
        load(UnmaskedDecoder::class.java)
        load(PrivateValue::class.java)
        load(IgnoredValue::class.java)
        load(PlainKeys::class.java)
    }

    open class Base
    class Branch(@field:Mask val secret: String = "raw") : Base()
    data class HiddenOneOf(@field:Schema(hidden = true, oneOf = [Branch::class]) val value: Base)
    data class HiddenAnyOf(@get:Schema(hidden = true, anyOf = [Branch::class]) val value: Base)
    data class OpaqueOneOf(
        @field:Schema(oneOf = [Branch::class])
        @get:JsonSerialize(using = OpaqueSerializer::class) val value: Base,
    )

    @JsonSubTypes(JsonSubTypes.Type(Subtype::class))
    open class SubtypeBase
    class Subtype(@field:Mask val secret: String = "raw") : SubtypeBase()
    data class OpaqueSubtype(@get:JsonSerialize(using = OpaqueSerializer::class) val value: SubtypeBase)

    @Schema(oneOf = [Branch::class])
    open class AlternativeBase
    data class HiddenClassAlternative(@field:Schema(hidden = true) val value: AlternativeBase)
    data class HiddenSubtype(@field:Schema(hidden = true) val value: SubtypeBase)

    @JsonIgnoreProperties(value = ["wire_secret"], allowGetters = true, allowSetters = true)
    data class RenamedIgnored(@field:JsonProperty("wire_secret") @field:Mask val secret: String)
    data class DocumentationWriteOnly(
        @field:Schema(accessMode = Schema.AccessMode.WRITE_ONLY) @field:Mask val secret: String
    )
    data class DocumentationWriteOnlyParent(@get:Schema(accessMode = Schema.AccessMode.WRITE_ONLY) val value: Branch)

    data class PropertyDecoder(@field:Mask @field:JsonDeserialize(using = RawDecoder::class) val secret: String)
    data class PropertyConverter(@field:Mask @get:JsonDeserialize(converter = RawConverter::class) val secret: String)
    data class ContentDecoder(@field:JsonDeserialize(contentUsing = BranchDecoder::class) val secrets: List<Branch>)
    data class ContentConverter(
        @get:JsonDeserialize(contentConverter = BranchConverter::class) val secrets: List<Branch>
    )

    @JsonDeserialize(using = TypeRawDecoder::class)
    data class TypeDecoder(@field:Mask val secret: String)
    data class UnmaskedDecoder(@field:JsonDeserialize(using = RawDecoder::class) val secret: String)

    data class ConstructorDecoder(@field:Mask @param:JsonDeserialize(using = RawDecoder::class) val secret: String)
    class SetterDecoder {
        @get:Mask
        @set:JsonDeserialize(using = RawDecoder::class)
        var secret: String = "raw"
    }
    class BranchDecoder : StdDeserializer<Branch>(Branch::class.java) {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): Branch {
            parser.skipChildren()
            return Branch("raw")
        }
    }
    class BranchConverter : StdConverter<Branch, Branch>() {
        override fun convert(value: Branch): Branch = Branch("raw")
    }

    @JsonDeserialize(converter = TypeRawConverter::class)
    data class TypeConverter(@field:Mask val secret: String)
    class TypeRawConverter : StdConverter<Branch, TypeConverter>() {
        override fun convert(value: Branch): TypeConverter = TypeConverter("raw")
    }

    @JsonDeserialize(using = ContainerRawDecoder::class)
    class ContainerDecoder : ArrayList<Branch>()
    class ContainerRawDecoder : StdDeserializer<ContainerDecoder>(ContainerDecoder::class.java) {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): ContainerDecoder {
            parser.skipChildren()
            return ContainerDecoder().apply { add(Branch("raw")) }
        }
    }

    class RawDecoder : StdDeserializer<String>(String::class.java) {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): String = "raw"
    }
    class RawConverter : StdConverter<String, String>() {
        override fun convert(value: String): String = "raw"
    }
    class TypeRawDecoder : StdDeserializer<TypeDecoder>(TypeDecoder::class.java) {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): TypeDecoder {
            parser.skipChildren()
            return TypeDecoder("raw")
        }
    }

    @JsonDeserialize(builder = BuilderValue.Builder::class)
    class BuilderValue private constructor(private val stored: String) {
        @get:Mask val secret: String get() = stored

        @JsonPOJOBuilder(withPrefix = "with")
        class Builder {
            private var value: String = ""
            fun withSecret(secret: String) = apply { value = secret }
            fun build(): BuilderValue = BuilderValue(value)
        }
    }

    data class SensitiveKey(@field:Mask val secret: String)
    data class MaskedKeys(val values: Map<SensitiveKey, String>)
    data class CustomMaskedKeys(
        @field:JsonSerialize(keyUsing = KeySerializer::class) val values: Map<SensitiveKey, String>
    )
    data class PlainKeys(val values: Map<String, String>)
    class KeySerializer : StdSerializer<SensitiveKey>(SensitiveKey::class.java) {
        override fun serialize(value: SensitiveKey, generator: JsonGenerator, provider: SerializationContext) {
            generator.writeName(value.secret)
        }
    }

    @JsonSerialize(using = OpaqueSerializer::class)
    @com.fasterxml.jackson.annotation.JsonAutoDetect(
        fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE
    )
    class PrivateOpaque(@Mask private val secret: String)

    @JsonSerialize(using = OpaqueSerializer::class)
    class IgnoredOpaque(@field:JsonIgnore @field:Mask val secret: String)

    @JsonSerialize(using = OpaqueSerializer::class)
    class PrivateOpaqueContainer(@field:JsonIgnore @field:Mask private val secret: String) : ArrayList<String>()

    class PrivateValue(@Mask private val secret: String)
    class IgnoredValue(@field:JsonIgnore @field:Mask val secret: String)
    data class PropertyPrivateOpaque(@field:JsonSerialize(using = OpaqueSerializer::class) val value: PrivateValue)
    open class PrivateParent(@Mask private val secret: String)

    @JsonSerialize(using = OpaqueSerializer::class)
    class InheritedPrivateOpaque : PrivateParent("raw")

    @Target(AnnotationTarget.PROPERTY)
    @Retention(AnnotationRetention.RUNTIME)
    @Mask
    annotation class PropertyMask

    @JsonSerialize(using = OpaqueSerializer::class)
    class ComputedOpaque {
        @PropertyMask @get:JsonIgnore
        val secret: String get() = "raw"
    }

    @JsonSerialize(using = OpaqueSerializer::class)
    class IgnoredNestedOpaque(@field:JsonIgnore val value: Branch)
    class OpaqueSerializer : StdSerializer<Any>(Any::class.java) {
        override fun serialize(value: Any, generator: JsonGenerator, provider: SerializationContext) {
            generator.writeString("raw")
        }
    }
}

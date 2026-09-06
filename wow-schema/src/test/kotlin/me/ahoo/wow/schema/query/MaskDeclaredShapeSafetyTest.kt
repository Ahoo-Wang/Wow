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

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside
import com.fasterxml.jackson.annotation.JsonIdentityInfo
import com.fasterxml.jackson.annotation.JsonIdentityReference
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.annotation.ObjectIdGenerators
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.OnSourcing
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.annotation.JsonDeserialize
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.ser.std.StdSerializer

class MaskDeclaredShapeSafetyTest {
    private fun load(type: Class<*>, model: QueryModel = QueryModel.SNAPSHOT) = JsonQuerySchemaSource(typeResolver = {
        type
    }).load(
        QuerySchemaContext(MaterializedNamedAggregate("test", "declared-shape"), model),
    ).single().block()!!

    @TestFactory
    fun `wrappers reject relocated masked fields`() = listOf(
        Wrapped() to "/contact/secret",
        ArrayWrapped() to "/1/secret",
        PropertyWrapper(Contact()) to "/contact/contact/secret",
        ContainerWrapper(listOf(Contact())) to "/contacts/0/1/secret",
        InheritedWrapper() to "/contact/secret",
        CombinedWrapper(Contact()) to "/contact/contact/secret",
    ).map { (value, path) ->
        DynamicTest.dynamicTest(value.javaClass.simpleName) {
            JsonSerializer.valueToTree<JsonNode>(value).at(path).stringValue().assert().isEqualTo("raw")
            assertThrows<QuerySchemaConflictException> { load(value.javaClass) }
        }
    }

    @TestFactory
    fun `identities reject scalar masks including repeated occurrences`() = listOf(
        IdentityState(IdentityContact()) to "/contact",
        PropertyIdentity(Contact()) to "/contact",
        AlwaysIdentityContact() to "",
        IdentityContact().let { RepeatedIdentity(it, it) } to "/second",
        IdentityContact().let { IdentityList(listOf(it, it)) } to "/contacts/1",
    ).map { (value, path) ->
        DynamicTest.dynamicTest(value.javaClass.simpleName) {
            JsonSerializer.valueToTree<JsonNode>(value).at(path).stringValue().assert().isEqualTo("raw")
            assertThrows<QuerySchemaConflictException> { load(value.javaClass) }
        }
    }

    @TestFactory
    fun `concrete deserialization declarations cannot hide masks`() = listOf(
        HiddenConcrete(Sensitive()) to "/value/secret",
        VisibleConcrete(Sensitive()) to "/value/secret",
        OpaqueConcrete(Sensitive()) to "/value/secret",
        HiddenContent(listOf(Sensitive())) to "/values/0/secret",
        HiddenTypeConcrete(TypeSensitive()) to "/value/secret",
        HiddenTypeContent(SensitiveList().apply { add(Sensitive()) }) to "/values/0/secret",
        HiddenKeys(mapOf(Sensitive() to "value")) to "/values/raw",
    ).map { (value, path) ->
        DynamicTest.dynamicTest(value.javaClass.simpleName) {
            JsonSerializer.valueToTree<JsonNode>(value).at(path).stringValue().assert().isEqualTo(
                if (value is HiddenKeys) "value" else "raw",
            )
            assertThrows<QuerySchemaConflictException> { load(value.javaClass) }
        }
    }

    @TestFactory
    fun `writable only alternatives do not establish generated mask paths`() = listOf(
        SetterOnlyAlternative(),
        UnbackedSetterAlternative(),
        CreatorOnlyAlternative(Sensitive()),
        SetterOnlySubtypes(),
    ).map { value ->
        DynamicTest.dynamicTest(value.javaClass.simpleName) {
            JsonSerializer.valueToTree<JsonNode>(value).at("/contact/secret").stringValue().assert().isEqualTo("raw")
            SchemaGeneratorBuilder().build().generateSchema(value.javaClass).toString()
                .contains("\"secret\"").assert().isFalse()
            assertThrows<QuerySchemaConflictException> { load(value.javaClass) }
        }
    }

    @Test
    fun `safe property overrides retain member masks`() {
        listOf(
            NoWrapper(Wrapped()) to "state.contact.secret",
            PropertyShape(Wrapped()) to "state.contact.secret",
            ExistingShape(Contact()) to "state.contact.secret",
            SameConcrete(Sensitive()) to "state.contact.secret",
            RepresentedConcrete(Sensitive()) to "state.contact.secret",
            GetterRepresentedConcrete(Sensitive()) to "state.contact.secret",
            GetterRepresentedSubtypes(Sensitive()) to "state.contact.secret",
        ).forEach { (value, path) ->
            JsonSerializer.valueToTree<JsonNode>(value).at("/contact/secret").stringValue().assert().isEqualTo("raw")
            val rule = load(value.javaClass).fields.getValue(QueryField(path)).maskRule as DeclarationValue.Set
            rule.value.compiled.mask("raw").assert().isEqualTo("***")
        }
    }

    @Test
    fun `unmasked shapes remain accepted`() {
        listOf(PlainIdentityState(PlainIdentity()), PlainOverride(PlainConcrete()), PlainWrapper()).forEach { value ->
            JsonSerializer.valueToTree<JsonNode>(value).isObject.assert().isTrue()
            load(value.javaClass)
        }
    }

    @TestFactory
    fun `event payloads reject redirected declarations`() = listOf(
        WrapperAggregate::class.java,
        IdentityAggregate::class.java,
        ConcreteAggregate::class.java,
    ).map { type ->
        DynamicTest.dynamicTest(type.simpleName) {
            assertThrows<QuerySchemaConflictException> { load(type, QueryModel.EVENT_STREAM) }
        }
    }

    @JsonTypeName("contact")
    data class Contact(@field:Mask val secret: String = "raw", val kind: String = "contact")

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
    @JsonTypeName("contact")
    data class Wrapped(@field:Mask val secret: String = "raw")

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_ARRAY)
    @JsonTypeName("contact")
    data class ArrayWrapped(@field:Mask val secret: String = "raw")
    data class PropertyWrapper(
        @get:JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT) val contact: Contact,
    )
    data class ContainerWrapper(
        @get:JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.WRAPPER_ARRAY
        ) val contacts: List<Contact>,
    )

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
    interface WrapperBase

    @JsonTypeName("contact")
    data class InheritedWrapper(@field:Mask val secret: String = "raw") : WrapperBase

    @JacksonAnnotationsInside
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
    @Target(AnnotationTarget.PROPERTY_GETTER)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class WrapperAnnotation
    data class CombinedWrapper(@get:WrapperAnnotation val contact: Contact)
    data class NoWrapper(@get:JsonTypeInfo(use = JsonTypeInfo.Id.NONE) val contact: Wrapped)
    data class PropertyShape(
        @get:JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY) val contact: Wrapped,
    )
    data class ExistingShape(
        @get:JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind")
        val contact: Contact,
    )

    @JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator::class, property = "secret")
    data class IdentityContact(@field:Mask val secret: String = "raw")

    @JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator::class, property = "secret")
    @JsonIdentityReference(alwaysAsId = true)
    data class AlwaysIdentityContact(@field:Mask val secret: String = "raw")
    data class IdentityState(@get:JsonIdentityReference(alwaysAsId = true) val contact: IdentityContact)
    data class PropertyIdentity(
        @get:JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator::class, property = "secret")
        @get:JsonIdentityReference(alwaysAsId = true) val contact: Contact,
    )
    data class RepeatedIdentity(val first: IdentityContact, val second: IdentityContact)
    data class IdentityList(val contacts: List<IdentityContact>)

    interface Base
    data class Sensitive(@field:Mask val secret: String = "raw") : Base {
        override fun toString(): String = secret
    }
    data class SameConcrete(@get:JsonDeserialize(`as` = Sensitive::class) val contact: Sensitive)
    data class RepresentedConcrete(
        @field:Schema(oneOf = [Sensitive::class]) @get:JsonDeserialize(`as` = Sensitive::class) val contact: Base,
    )
    class SetterOnlyAlternative {
        @get:JsonDeserialize(`as` = Sensitive::class)
        @set:Schema(oneOf = [Sensitive::class])
        var contact: Base = Sensitive()
    }

    class UnbackedSetterAlternative {
        private var stored: Base = Sensitive()

        @get:JsonDeserialize(`as` = Sensitive::class)
        @set:Schema(oneOf = [Sensitive::class])
        var contact: Base
            get() = stored
            set(value) { stored = value }
    }

    data class CreatorOnlyAlternative(
        @get:JsonDeserialize(`as` = Sensitive::class)
        @param:Schema(oneOf = [Sensitive::class]) val contact: Base,
    )

    class SetterOnlySubtypes {
        @get:JsonDeserialize(`as` = Sensitive::class)
        @set:JsonSubTypes(JsonSubTypes.Type(Sensitive::class))
        var contact: Base = Sensitive()
    }

    data class GetterRepresentedConcrete(
        @get:Schema(oneOf = [Sensitive::class]) @get:JsonDeserialize(`as` = Sensitive::class) val contact: Base,
    )

    data class GetterRepresentedSubtypes(
        @get:JsonSubTypes(JsonSubTypes.Type(Sensitive::class))
        @get:JsonDeserialize(`as` = Sensitive::class) val contact: Base,
    )

    data class VisibleConcrete(@get:JsonDeserialize(`as` = Sensitive::class) val value: Base)
    data class HiddenConcrete(
        @field:Schema(
            hidden = true
        ) @get:JsonDeserialize(`as` = Sensitive::class) val value: Base
    )
    data class OpaqueConcrete(
        @get:JsonSerialize(using = RawBaseSerializer::class)
        @get:JsonDeserialize(`as` = Sensitive::class) val value: Base,
    )
    class RawBaseSerializer : StdSerializer<Base>(Base::class.java) {
        override fun serialize(
            value: Base,
            gen: JsonGenerator,
            context: SerializationContext,
        ) {
            gen.writeStartObject()
            gen.writeStringProperty("secret", (value as Sensitive).secret)
            gen.writeEndObject()
        }
    }
    data class HiddenContent(
        @field:Schema(hidden = true) @get:JsonDeserialize(contentAs = Sensitive::class) val values: List<Base>,
    )

    @JsonDeserialize(`as` = TypeSensitive::class)
    interface TypeBase
    data class TypeSensitive(@field:Mask val secret: String = "raw") : TypeBase
    data class HiddenTypeConcrete(@field:Schema(hidden = true) val value: TypeBase)

    @JsonDeserialize(contentAs = Sensitive::class)
    class SensitiveList : ArrayList<Base>()
    data class HiddenTypeContent(@field:Schema(hidden = true) val values: SensitiveList)
    data class HiddenKeys(
        @field:Schema(hidden = true) @get:JsonDeserialize(keyAs = Sensitive::class) val values: Map<Base, String>,
    )

    @JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator::class, property = "secret")
    data class PlainIdentity(val secret: String = "raw")
    data class PlainIdentityState(@get:JsonIdentityReference(alwaysAsId = true) val contact: PlainIdentity)
    data class PlainConcrete(val secret: String = "raw") : Base
    data class PlainOverride(@get:JsonDeserialize(`as` = PlainConcrete::class) val value: Base)

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
    data class PlainWrapper(val secret: String = "raw")

    @AggregateRoot
    class WrapperAggregate(val id: String) {
        var value: Wrapped? = null

        @OnSourcing
        fun onEvent(event: Wrapped) { value = event }
    }

    @AggregateRoot
    class IdentityAggregate(val id: String) {
        var value: IdentityState? = null

        @OnSourcing
        fun onEvent(event: IdentityState) { value = event }
    }

    @AggregateRoot
    class ConcreteAggregate(val id: String) {
        var value: HiddenConcrete? = null

        @OnSourcing
        fun onEvent(event: HiddenConcrete) { value = event }
    }
}

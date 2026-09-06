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
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.ser.std.StdSerializer

class MaskContextualShapeSafetyTest {
    private fun load(type: Class<*>, model: QueryModel = QueryModel.SNAPSHOT) =
        JsonQuerySchemaSource(typeResolver = { type }).load(
            QuerySchemaContext(MaterializedNamedAggregate("test", "mask-contextual-shape"), model),
        ).single().block()!!

    @TestFactory
    fun `type container overrides reject relocated masked content`() = listOf(
        RenamedContacts().apply { add(Contact()) } to "/0/renamed",
        ContainerState(RenamedContacts().apply { add(Contact()) }) to "/contacts/0/renamed",
    ).map { (value, path) ->
        DynamicTest.dynamicTest(value.javaClass.simpleName) {
            JsonSerializer.valueToTree<JsonNode>(value).at(path).stringValue().assert().isEqualTo("raw")
            assertThrows<QuerySchemaConflictException> { load(value.javaClass) }
        }
    }

    @TestFactory
    fun `contextual arrays reject member oriented masks`() = listOf(
        ArrayContact() to "/0",
        ArrayState(Contact()) to "/contact/0",
        ArrayListState(listOf(Contact())) to "/contacts/0/0",
        ReusedState(Contact(), Contact()) to "/array/0",
        NestedArrayState(ArrayState(Contact())) to "/nested/contact/0",
    ).map { (value, path) ->
        DynamicTest.dynamicTest(value.javaClass.simpleName) {
            JsonSerializer.valueToTree<JsonNode>(value).at(path).stringValue().assert().isEqualTo("raw")
            assertThrows<QuerySchemaConflictException> { load(value.javaClass) }
        }
    }

    @TestFactory
    fun `writable only composed masks cannot silently lose their rule`() = listOf(
        SetterOnly(),
        CreatorOnly(),
    ).map { value ->
        DynamicTest.dynamicTest(value.javaClass.simpleName) {
            JsonSerializer.valueToTree<JsonNode>(value).path("secret").stringValue().assert().isEqualTo("raw")
            assertThrows<QuerySchemaConflictException> { load(value.javaClass) }
        }
    }

    @Test
    fun `ordinary containers and explicit object shape retain masks`() {
        val value = ObjectState(ArrayContact())
        JsonSerializer.valueToTree<JsonNode>(value).at("/contact/secret").stringValue().assert().isEqualTo("raw")
        listOf(
            ObjectState::class.java to "state.contact.secret",
            OrdinaryState::class.java to "state.contacts.secret",
            BackedSetter::class.java to "state.secret",
            VisibleComposed::class.java to "state.secret",
        ).forEach { (type, path) ->
            val rule = load(type).fields.getValue(QueryField(path)).maskRule as DeclarationValue.Set
            rule.value.compiled.mask("raw").assert().isEqualTo("***")
        }
    }

    @TestFactory
    fun `event payload validation rejects contextual and writable mask gaps`() = listOf(
        ContainerAggregate::class.java,
        ArrayAggregate::class.java,
        SetterAggregate::class.java,
    ).map { type ->
        DynamicTest.dynamicTest(type.simpleName) {
            assertThrows<QuerySchemaConflictException> { load(type, QueryModel.EVENT_STREAM) }
        }
    }

    data class Contact(@field:Mask val secret: String = "raw")

    @JsonSerialize(contentUsing = RenamedContactSerializer::class)
    class RenamedContacts : ArrayList<Contact>()
    data class ContainerState(val contacts: RenamedContacts)
    class RenamedContactSerializer : StdSerializer<Contact>(Contact::class.java) {
        override fun serialize(value: Contact, gen: JsonGenerator, context: SerializationContext) {
            gen.writeStartObject()
            gen.writeStringProperty("renamed", value.secret)
            gen.writeEndObject()
        }
    }

    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    data class ArrayContact(@field:Mask val secret: String = "raw")
    data class ArrayState(@get:JsonFormat(shape = JsonFormat.Shape.ARRAY) val contact: Contact)
    data class ArrayListState(@get:JsonFormat(shape = JsonFormat.Shape.ARRAY) val contacts: List<Contact>)
    data class ReusedState(val ordinary: Contact, @get:JsonFormat(shape = JsonFormat.Shape.ARRAY) val array: Contact)
    data class NestedArrayState(val nested: ArrayState)
    data class ObjectState(@get:JsonFormat(shape = JsonFormat.Shape.OBJECT) val contact: ArrayContact)
    data class OrdinaryState(val contacts: List<Contact>)

    @Mask
    @Target(
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.FIELD,
        AnnotationTarget.PROPERTY_GETTER
    )
    @Retention(AnnotationRetention.RUNTIME)
    annotation class ComposedMask
    class SetterOnly {
        private var stored: String = "raw"

        @set:ComposedMask
        var secret: String
            get() = stored
            set(value) { stored = value }
    }
    class BackedSetter {
        @set:ComposedMask
        var secret: String = "raw"
    }
    data class CreatorOnly(@param:ComposedMask val secret: String = "raw")
    data class VisibleComposed(@field:ComposedMask @param:ComposedMask val secret: String = "raw")

    @AggregateRoot
    class ContainerAggregate(val id: String) {
        var value: ContainerState? = null

        @OnSourcing
        fun onEvent(event: ContainerState) { value = event }
    }

    @AggregateRoot
    class ArrayAggregate(val id: String) {
        var value: ArrayState? = null

        @OnSourcing
        fun onEvent(event: ArrayState) { value = event }
    }

    @AggregateRoot
    class SetterAggregate(val id: String) {
        var value: SetterOnly? = null

        @OnSourcing
        fun onEvent(event: SetterOnly) { value = event }
    }
}

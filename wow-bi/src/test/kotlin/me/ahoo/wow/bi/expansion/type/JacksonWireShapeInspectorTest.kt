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

package me.ahoo.wow.bi.expansion.type

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.fasterxml.jackson.annotation.JsonValue
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.bi.expansion.BIAggregateState
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.ser.std.StdSerializer
import tools.jackson.databind.util.StdConverter

class JacksonWireShapeInspectorTest {
    @Test
    fun `should expand an ordinary concrete bean`() {
        val shape = inspect<OrdinaryBean>()

        shape.assert().isInstanceOf(JsonWireShape.ExpandableObject::class.java)
        (shape as JsonWireShape.ExpandableObject).properties.map { it.serializedName }
            .assert().containsExactly("name")
    }

    @Test
    fun `should expand an ordinary aggregate state`() {
        val shape = inspect<BIAggregateState>()

        shape.assert().isInstanceOf(JsonWireShape.ExpandableObject::class.java)
    }

    @Test
    fun `should mark interface as opaque`() {
        assertOpaque<PaymentContract>(JsonWireShapeReason.ABSTRACT_OR_POLYMORPHIC)
    }

    @Test
    fun `should mark abstract class as opaque`() {
        assertOpaque<AbstractPayment>(JsonWireShapeReason.ABSTRACT_OR_POLYMORPHIC)
    }

    @Test
    fun `should mark sealed interface as opaque`() {
        assertOpaque<Payment>(JsonWireShapeReason.ABSTRACT_OR_POLYMORPHIC)
    }

    @Test
    fun `should mark Jackson polymorphic type as opaque`() {
        assertOpaque<PolymorphicPayment>(JsonWireShapeReason.ABSTRACT_OR_POLYMORPHIC)
    }

    @Test
    fun `should mark JsonValue type as opaque`() {
        assertOpaque<JsonValueIdentifier>(JsonWireShapeReason.NON_OBJECT_FORMAT)
    }

    @Test
    fun `should mark JsonUnwrapped object as opaque`() {
        assertOpaque<UnwrappedHolder>(JsonWireShapeReason.CUSTOM_SERIALIZATION)
    }

    @Test
    fun `should mark JsonAnyGetter object as opaque`() {
        assertOpaque<AnyGetterHolder>(JsonWireShapeReason.CUSTOM_SERIALIZATION)
    }

    @Test
    fun `should mark class custom serializer as opaque`() {
        assertOpaque<ClassSerializedBean>(JsonWireShapeReason.CUSTOM_SERIALIZATION)
    }

    @Test
    fun `should mark property serializer as opaque`() {
        assertOpaque<PropertySerializerHolder>(JsonWireShapeReason.CUSTOM_SERIALIZATION)
    }

    @Test
    fun `should mark content serializer as opaque`() {
        assertOpaque<ContentSerializerHolder>(JsonWireShapeReason.CUSTOM_SERIALIZATION)
    }

    @Test
    fun `should mark key serializer as opaque`() {
        assertOpaque<KeySerializerHolder>(JsonWireShapeReason.CUSTOM_SERIALIZATION)
    }

    @Test
    fun `should mark property converter as opaque`() {
        assertOpaque<PropertyConverterHolder>(JsonWireShapeReason.CUSTOM_SERIALIZATION)
    }

    @Test
    fun `should mark aggregate id custom serializer as opaque`() {
        assertOpaque<AggregateId>(JsonWireShapeReason.CUSTOM_SERIALIZATION)
    }

    private inline fun <reified T> inspect(): JsonWireShape {
        return JacksonWireShapeInspector.inspect(
            ResolvedType(
                javaType = JsonSerializer.constructType(T::class.java),
                nullability = Nullability.NON_NULL,
                arguments = emptyList(),
            )
        )
    }

    private inline fun <reified T> assertOpaque(reason: JsonWireShapeReason) {
        val shape = inspect<T>()

        shape.assert().isInstanceOf(JsonWireShape.Opaque::class.java)
        (shape as JsonWireShape.Opaque).reason.assert().isEqualTo(reason)
    }
}

private data class OrdinaryBean(val name: String)

private interface PaymentContract

private abstract class AbstractPayment

private sealed interface Payment

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
private open class PolymorphicPayment(val name: String)

private data class JsonValueIdentifier(@get:JsonValue val value: String)

private data class UnwrappedDetails(val name: String)

private data class UnwrappedHolder(
    @get:JsonUnwrapped
    val details: UnwrappedDetails,
)

private class AnyGetterHolder {
    @JsonAnyGetter
    fun attributes(): Map<String, String> = emptyMap()
}

@JsonSerialize(using = ClassSerializedBeanSerializer::class)
private data class ClassSerializedBean(val value: String)

private class ClassSerializedBeanSerializer : StdSerializer<ClassSerializedBean>(ClassSerializedBean::class.java) {
    override fun serialize(
        value: ClassSerializedBean,
        generator: JsonGenerator,
        provider: SerializationContext,
    ) {
        generator.writeString(value.value)
    }
}

private data class PropertySerializerHolder(
    @get:JsonSerialize(using = StringValueSerializer::class)
    val value: String,
)

private data class ContentSerializerHolder(
    @get:JsonSerialize(contentUsing = StringValueSerializer::class)
    val values: List<String>,
)

private data class KeySerializerHolder(
    @get:JsonSerialize(keyUsing = StringValueSerializer::class)
    val values: Map<String, String>,
)

private data class PropertyConverterHolder(
    @get:JsonSerialize(converter = StringValueConverter::class)
    val value: String,
)

private class StringValueSerializer : StdSerializer<String>(String::class.java) {
    override fun serialize(value: String, generator: JsonGenerator, provider: SerializationContext) {
        generator.writeString(value)
    }
}

private class StringValueConverter : StdConverter<String, String>() {
    override fun convert(value: String): String = value
}

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
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonRawValue
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.annotation.JsonUnwrapped
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.BeanDescription
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JavaType
import tools.jackson.databind.SerializationConfig
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.ext.jdk8.Jdk8OptionalSerializer
import tools.jackson.databind.jsontype.TypeSerializer
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.ser.BeanPropertyWriter
import tools.jackson.databind.ser.BeanSerializer
import tools.jackson.databind.ser.ValueSerializerModifier
import tools.jackson.databind.ser.bean.BeanSerializerBase
import tools.jackson.databind.ser.bean.UnwrappingBeanPropertyWriter
import tools.jackson.databind.ser.std.StdContainerSerializer
import tools.jackson.databind.ser.std.StdSerializer
import tools.jackson.databind.type.ArrayType
import tools.jackson.databind.type.CollectionType
import tools.jackson.databind.type.ReferenceType
import tools.jackson.databind.util.NameTransformer
import java.util.Optional

data class RegisteredMaskedValue(
    @field:Mask val secret: String,
    @field:JsonIgnore val received: String = "native-bean-deserializer",
)

data class RegisteredPropertyState(val value: RegisteredMaskedValue)

data class RegisteredListState(val values: List<RegisteredMaskedValue>)

class RegisteredMaskTestModule : SimpleModule() {
    init {
        addDeserializer(RegisteredMaskedValue::class.java, RegisteredMaskedValueDeserializer())
        setSerializerModifier(RegisteredMaskSerializerModifier())
        addSerializer(FlattenedMaskedList::class.java, FlattenedMaskedListSerializer())
    }
}

private class RegisteredMaskedValueDeserializer : StdDeserializer<RegisteredMaskedValue>(
    RegisteredMaskedValue::class.java,
) {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): RegisteredMaskedValue {
        val received = parser.readValueAsTree<ObjectNode>().path("secret").stringValue()
        return RegisteredMaskedValue(secret = received, received = received)
    }
}

@JsonTypeName("element")
data class BoundTypeMaskedElement(@field:Mask val secret: String)
data class BoundTypeListState(val values: List<BoundTypeMaskedElement>)
data class BoundTypeArrayState(val values: Array<BoundTypeMaskedElement>)

@JsonTypeName("element")
data class PropertyTypeMaskedElement(@field:Mask val secret: String)
data class PropertyTypeListState(val values: List<PropertyTypeMaskedElement>)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
private interface BoundContainerPropertyShape

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
private interface BoundContainerTypeShape

data class BoundSerializerState(val value: NativeMaskedElement)
data class DuplicateMaskedBean(@field:Mask val secret: String)
data class OverriddenWriterBean(@field:Mask val secret: String)
data class NativeMaskedElement(@field:Mask val secret: String)
data class NativeMaskedLayout(
    @field:JsonProperty("wire_secret") @field:Mask val secret: String,
    val values: List<NativeMaskedElement>,
    val elements: Array<NativeMaskedElement>,
    val labels: Map<String, String>,
)
data class RenamedMaskedBean(@field:Mask val secret: String)
data class OverriddenMaskedBean(@field:Mask val secret: String)
data class ReorderedMaskedBean(@field:Mask val secret: String, val label: String = "visible")
data class SerializerMaskedElement(@field:Mask val secret: String)
class FlattenedMaskedList : ArrayList<SerializerMaskedElement>()
data class FlattenedMaskedState(val values: FlattenedMaskedList)
data class ResolvedMaskedListState(val values: List<SerializerMaskedElement>)

private class RegisteredMaskSerializerModifier : ValueSerializerModifier() {
    @Suppress("UNCHECKED_CAST")
    override fun changeProperties(
        config: SerializationConfig,
        beanDesc: BeanDescription.Supplier,
        beanProperties: MutableList<BeanPropertyWriter>,
    ): MutableList<BeanPropertyWriter> = when (beanDesc.beanClass) {
        RenamedMaskedBean::class.java -> beanProperties.map {
            it.rename(NameTransformer.simpleTransformer("leaked_", ""))
        }.toMutableList()
        DuplicateMaskedBean::class.java -> beanProperties.apply {
            add(first().rename(NameTransformer.simpleTransformer("leaked_", "")))
        }
        OverriddenWriterBean::class.java -> beanProperties.apply { replaceAll(::RenamingPropertyWriter) }
        BoundNativeOptionalState::class.java -> beanProperties.onEach {
            val optional = Jdk8OptionalSerializer(
                it.type as ReferenceType,
                false,
                null,
                OptionalRenamingSerializer(),
            )
            it.assignSerializer(optional as ValueSerializer<Any>)
        }
        DynamicUnwrappedMismatchState::class.java -> beanProperties.apply {
            replaceAll { UnwrappingBeanPropertyWriter(it, NameTransformer.simpleTransformer("rogue_", "")) }
        }
        BoundSerializerState::class.java -> beanProperties.onEach { it.assignSerializer(RenamingValueSerializer()) }
        ReorderedMaskedBean::class.java -> beanProperties.reversed().toMutableList()
        else -> beanProperties
    }

    override fun modifyCollectionSerializer(
        config: SerializationConfig,
        valueType: CollectionType,
        beanDesc: BeanDescription.Supplier,
        serializer: ValueSerializer<*>,
    ): ValueSerializer<*> = if (valueType.contentType.rawClass == SerializerMaskedElement::class.java) {
        FlattenedMaskedListSerializer()
    } else if (valueType.contentType.rawClass == BoundTypeMaskedElement::class.java) {
        withContentWrapper(serializer)
    } else if (valueType.contentType.rawClass == PropertyTypeMaskedElement::class.java) {
        withContentWrapper(serializer, BoundContainerPropertyShape::class.java)
    } else {
        serializer
    }

    override fun modifyArraySerializer(
        config: SerializationConfig,
        valueType: ArrayType,
        beanDesc: BeanDescription.Supplier,
        serializer: ValueSerializer<*>,
    ): ValueSerializer<*> = if (valueType.contentType.rawClass == BoundTypeMaskedElement::class.java) {
        withContentWrapper(serializer)
    } else {
        serializer
    }

    private fun withContentWrapper(
        serializer: ValueSerializer<*>,
        shape: Class<*> = BoundContainerTypeShape::class.java,
    ): ValueSerializer<*> {
        val wrapper = JsonSerializer._serializationContext().findTypeSerializer(
            JsonSerializer.typeFactory.constructType(shape),
        )
        return (serializer as StdContainerSerializer<*>).withValueTypeSerializer(wrapper).also {
            check(it.javaClass == serializer.javaClass)
        }
    }

    override fun modifySerializer(
        config: SerializationConfig,
        beanDesc: BeanDescription.Supplier,
        serializer: ValueSerializer<*>,
    ): ValueSerializer<*> = if (beanDesc.beanClass == OverriddenMaskedBean::class.java) {
        RenamingBeanSerializer(serializer as BeanSerializerBase)
    } else {
        serializer
    }
}

private class RenamingBeanSerializer(source: BeanSerializerBase) :
    BeanSerializer(source) {
    override fun serialize(value: Any, generator: JsonGenerator, context: SerializationContext) {
        generator.writeStartObject()
        generator.writeStringProperty("leaked_secret", (value as OverriddenMaskedBean).secret)
        generator.writeEndObject()
    }
}

private class FlattenedMaskedListSerializer : StdContainerSerializer<List<SerializerMaskedElement>>(
    FlattenedMaskedList::class.java,
) {
    override fun getContentType(): JavaType =
        JsonSerializer.typeFactory.constructType(SerializerMaskedElement::class.java)
    override fun getContentSerializer(): ValueSerializer<*>? = null
    override fun hasSingleElement(value: List<SerializerMaskedElement>): Boolean = value.size == 1
    override fun isEmpty(context: SerializationContext, value: List<SerializerMaskedElement>): Boolean = value.isEmpty()
    override fun _withValueTypeSerializer(serializer: TypeSerializer): StdContainerSerializer<*> = this
    override fun serialize(
        value: List<SerializerMaskedElement>,
        generator: JsonGenerator,
        context: SerializationContext
    ) {
        generator.writeStartArray()
        value.forEach {
            generator.writeStartObject()
            generator.writeStringProperty("leaked_secret", it.secret)
            generator.writeEndObject()
        }
        generator.writeEndArray()
    }
}

private class RenamingValueSerializer : StdSerializer<Any>(Any::class.java) {
    override fun serialize(value: Any, generator: JsonGenerator, context: SerializationContext) {
        generator.writeStartObject()
        generator.writeStringProperty("leaked_secret", (value as NativeMaskedElement).secret)
        generator.writeEndObject()
    }
}

private class RenamingPropertyWriter(source: BeanPropertyWriter) : BeanPropertyWriter(source) {
    override fun serializeAsProperty(value: Any, generator: JsonGenerator, context: SerializationContext) {
        generator.writeStringProperty("leaked_secret", (value as OverriddenWriterBean).secret)
    }
}

data class NativeOptionalState(val value: Optional<NativeMaskedElement>)
class NativeUnwrappedState {
    @get:JsonUnwrapped(prefix = "pre_", suffix = "_s")
    var value: NativeMaskedElement = NativeMaskedElement("native-secret")
}
class MismatchedNestedUnwrappedState {
    @get:JsonUnwrapped(prefix = "outer_", suffix = "_out")
    var value: NativeUnwrappedState = NativeUnwrappedState()
}
data class HiddenIteratorState(@get:Schema(hidden = true) val values: Iterator<NativeMaskedElement>)
data class VisibleIteratorState(val values: Iterator<NativeMaskedElement>)

open class NativeOpenMaskedElement {
    @get:Mask var secret: String = "native-secret"
}
class NativeUnwrappedMiddle {
    @get:JsonUnwrapped(prefix = "pre_", suffix = "_s")
    var value: NativeOpenMaskedElement = NativeOpenMaskedElement()
}
class NativeNestedUnwrappedState {
    @get:JsonUnwrapped(prefix = "outer_", suffix = "_out")
    var value: NativeUnwrappedMiddle = NativeUnwrappedMiddle()
}
class NativePlainUnwrappedState {
    @get:JsonUnwrapped
    var value: NativeMaskedElement = NativeMaskedElement("native-secret")
}

data class OptionalBoundElement(@field:Mask val secret: String)
data class BoundNativeOptionalState(val value: Optional<OptionalBoundElement>)
class DynamicUnwrappedMismatchState {
    @get:JsonUnwrapped(prefix = "pre_", suffix = "_s")
    var value: NativeOpenMaskedElement = NativeOpenMaskedElement().apply { secret = "spi-backend-secret" }
}
private class OptionalRenamingSerializer : StdSerializer<Any>(Any::class.java) {
    override fun serialize(value: Any, generator: JsonGenerator, context: SerializationContext) {
        generator.writeStartObject()
        generator.writeStringProperty("leaked_secret", (value as OptionalBoundElement).secret)
        generator.writeEndObject()
    }
}

data class PlainIteratorState(val values: Iterator<String>)

data class RawMaskedString(@field:Mask @field:JsonRawValue val secret: String)
data class GetterRawMaskedString(@get:Mask @get:JsonRawValue @field:JsonRawValue(false) val secret: String)
data class DisabledRawMaskedString(@field:Mask @field:JsonRawValue(false) val secret: String)
class GetterOverridesRawString {
    @get:Mask
    @get:JsonRawValue(false)
    @field:JsonRawValue
    var secret: String = ""
}
class SetterRawString {
    @get:Mask
    @get:JsonRawValue(false)
    @set:JsonRawValue
    var secret: String = ""
}
data class IgnoredRawString(
    @field:Mask val secret: String,
    @field:Mask @field:JsonRawValue @field:JsonIgnore val ignored: String = "{}",
)

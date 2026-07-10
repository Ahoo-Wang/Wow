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

import me.ahoo.wow.bi.expansion.plan.PropertyFilter
import me.ahoo.wow.bi.type.JsonTokenShape
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toBeanDescription
import tools.jackson.databind.BeanProperty
import tools.jackson.databind.JavaType
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.introspect.Annotated
import tools.jackson.databind.introspect.AnnotatedMember
import tools.jackson.databind.introspect.BeanPropertyDefinition
import tools.jackson.databind.jsonFormatVisitors.JsonBooleanFormatVisitor
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitable
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper
import tools.jackson.databind.jsonFormatVisitors.JsonIntegerFormatVisitor
import tools.jackson.databind.jsonFormatVisitors.JsonNumberFormatVisitor
import tools.jackson.databind.jsonFormatVisitors.JsonObjectFormatVisitor
import tools.jackson.databind.jsonFormatVisitors.JsonStringFormatVisitor
import tools.jackson.databind.ser.bean.BeanSerializerBase
import tools.jackson.databind.ser.impl.UnknownSerializer
import java.lang.reflect.Modifier

internal object JacksonWireShapeInspector {
    fun matches(type: ResolvedType, expected: JsonTokenShape): Boolean {
        if (type.javaType.toBeanDescription().findJsonValueAccessor() != null) {
            return false
        }
        val visitor = TokenShapeVisitor()
        JsonSerializer.acceptJsonFormatVisitor(type.javaType, visitor)
        return visitor.shape == expected
    }

    fun inspect(type: ResolvedType): JsonWireShape {
        val beanDescription = type.javaType.toBeanDescription()
        val context = JsonSerializer._serializationContext()

        if (hasExplicitCustomSerialization(beanDescription.classInfo, beanDescription.findProperties())) {
            return JsonWireShape.Opaque(JsonWireShapeReason.CUSTOM_SERIALIZATION)
        }
        if (beanDescription.findJsonValueAccessor() != null) {
            return JsonWireShape.Opaque(JsonWireShapeReason.NON_OBJECT_FORMAT)
        }
        if (beanDescription.findAnyGetter() != null) {
            return JsonWireShape.Opaque(JsonWireShapeReason.CUSTOM_SERIALIZATION)
        }

        val selectedSerializer = context.findValueSerializer(type.javaType)
        if (selectedSerializer !is BeanSerializerBase &&
            selectedSerializer !is UnknownSerializer
        ) {
            return JsonWireShape.Opaque(JsonWireShapeReason.CUSTOM_SERIALIZATION)
        }

        if (isAbstractOrPolymorphic(type, beanDescription.classInfo)) {
            return JsonWireShape.Opaque(JsonWireShapeReason.ABSTRACT_OR_POLYMORPHIC)
        }

        val properties = JsonPropertyTypeResolver.resolve(type)
        val visitor = ObjectShapeVisitor()
        JsonSerializer.acceptJsonFormatVisitor(type.javaType, visitor)
        val wireProperties = visitor.properties
            ?: return JsonWireShape.Opaque(JsonWireShapeReason.NON_OBJECT_FORMAT)
        val expectedSignatures = properties.map {
            PropertySignature(it.serializedName, it.type.javaType.toCanonical())
        }.sortedBy(PropertySignature::name)
        if (wireProperties.sortedBy(PropertySignature::name) != expectedSignatures) {
            return JsonWireShape.Opaque(JsonWireShapeReason.PROPERTY_SIGNATURE_MISMATCH)
        }
        return JsonWireShape.ExpandableObject(properties)
    }

    private fun isAbstractOrPolymorphic(type: ResolvedType, classInfo: Annotated): Boolean {
        val rawClass = type.rawClass
        val config = JsonSerializer.serializationConfig()
        val context = JsonSerializer._serializationContext()
        val abstractType = rawClass.isInterface ||
            Modifier.isAbstract(rawClass.modifiers) ||
            rawClass.isSealed
        val polymorphicType = context.findTypeSerializer(type.javaType) != null ||
            config.annotationIntrospector.findPolymorphicTypeInfo(config, classInfo) != null
        return abstractType || polymorphicType
    }

    private fun hasExplicitCustomSerialization(
        classInfo: Annotated,
        properties: List<BeanPropertyDefinition>,
    ): Boolean {
        val config = JsonSerializer.serializationConfig()
        val introspector = config.annotationIntrospector
        if (introspector.findSerializer(config, classInfo) != null ||
            introspector.findSerializationConverter(config, classInfo) != null
        ) {
            return true
        }
        return properties.asSequence()
            .filter(PropertyFilter::shouldInclude)
            .mapNotNull { property -> property.primaryMember ?: property.accessor }
            .any { member -> member.hasCustomSerialization() }
    }

    private fun AnnotatedMember.hasCustomSerialization(): Boolean {
        val config = JsonSerializer.serializationConfig()
        val introspector = config.annotationIntrospector
        if (getAnnotation(JsonSerialize::class.java) != null) {
            return true
        }
        val serializationConverter = introspector.findSerializationConverter(config, this)
        val contentConverter = introspector.findSerializationContentConverter(config, this)
        return introspector.findSerializer(config, this) != null ||
            introspector.findContentSerializer(config, this) != null ||
            introspector.findKeySerializer(config, this) != null ||
            serializationConverter.isExplicitConverter() ||
            contentConverter.isExplicitConverter() ||
            introspector.findUnwrappingNameTransformer(config, this) != null ||
            introspector.findPolymorphicTypeInfo(config, this) != null
    }

    private fun Any?.isExplicitConverter(): Boolean {
        return this != null && !javaClass.name.startsWith(KOTLIN_MODULE_PACKAGE_PREFIX)
    }

    private data class PropertySignature(val name: String, val type: String)

    private class ObjectShapeVisitor : JsonFormatVisitorWrapper.Base() {
        var properties: MutableList<PropertySignature>? = null
            private set

        override fun expectObjectFormat(type: JavaType): JsonObjectFormatVisitor {
            val signatures = mutableListOf<PropertySignature>()
            properties = signatures
            return object : JsonObjectFormatVisitor.Base(context) {
                override fun property(writer: BeanProperty) {
                    signatures.add(PropertySignature(writer.name, writer.type.toCanonical()))
                }

                override fun optionalProperty(writer: BeanProperty) {
                    property(writer)
                }

                override fun property(name: String, handler: JsonFormatVisitable, propertyTypeHint: JavaType) {
                    signatures.add(PropertySignature(name, propertyTypeHint.toCanonical()))
                }

                override fun optionalProperty(
                    name: String,
                    handler: JsonFormatVisitable,
                    propertyTypeHint: JavaType,
                ) {
                    property(name, handler, propertyTypeHint)
                }
            }
        }
    }

    private class TokenShapeVisitor : JsonFormatVisitorWrapper.Base() {
        var shape: JsonTokenShape? = null
            private set

        override fun expectStringFormat(type: JavaType): JsonStringFormatVisitor {
            shape = JsonTokenShape.STRING
            return JsonStringFormatVisitor.Base()
        }

        override fun expectIntegerFormat(type: JavaType): JsonIntegerFormatVisitor {
            shape = JsonTokenShape.INTEGER
            return JsonIntegerFormatVisitor.Base()
        }

        override fun expectNumberFormat(type: JavaType): JsonNumberFormatVisitor {
            shape = JsonTokenShape.NUMBER
            return JsonNumberFormatVisitor.Base()
        }

        override fun expectBooleanFormat(type: JavaType): JsonBooleanFormatVisitor {
            shape = JsonTokenShape.BOOLEAN
            return JsonBooleanFormatVisitor.Base()
        }
    }

    private const val KOTLIN_MODULE_PACKAGE_PREFIX = "tools.jackson.module.kotlin."
}

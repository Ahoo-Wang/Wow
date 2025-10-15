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

package me.ahoo.wow.schema.jackson

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside
import com.fasterxml.jackson.annotation.JsonBackReference
import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition
import com.github.victools.jsonschema.generator.FieldScope
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.module.jackson.JacksonModule
import com.github.victools.jsonschema.module.jackson.JacksonOption
import me.ahoo.wow.schema.Types.isKotlinElement

class WowJacksonModule(vararg options: JacksonOption) : JacksonModule(*options) {
    companion object {
        val NESTED_ANNOTATION_CHECK: (Annotation) -> Boolean = { annotation ->
            annotation.javaClass.isAnnotationPresent(JacksonAnnotationsInside::class.java)
        }
    }

    private lateinit var objectMapper: ObjectMapper

    override fun applyToConfigBuilder(builder: SchemaGeneratorConfigBuilder) {
        this.objectMapper = builder.objectMapper
        super.applyToConfigBuilder(builder)
    }

    override fun shouldIgnoreField(field: FieldScope): Boolean {
        if (field.getAnnotationConsideringFieldAndGetterIfSupported(
                JsonBackReference::class.java,
                NESTED_ANNOTATION_CHECK
            ) != null
        ) {
            return true
        }

        // @since 4.32.0
        val unwrappedAnnotation = field.getAnnotationConsideringFieldAndGetterIfSupported(
            JsonUnwrapped::class.java,
            NESTED_ANNOTATION_CHECK
        )
        if (unwrappedAnnotation != null && unwrappedAnnotation.enabled) {
            // unwrapped properties should be ignored here, as they are included in their unwrapped form
            return true
        }

        // instead of re-creating the various ways a property may be included/excluded in jackson: just use its built-in introspection
        val topMostHierarchyType = field.declaringTypeMembers.allTypesAndOverrides()[0]
        val beanDescription = this.getBeanDescriptionForClass(topMostHierarchyType.type)

        // some kinds of field ignorals are only available via an annotation introspector
        val ignoredProperties = this.objectMapper.serializationConfig.annotationIntrospector
            .findPropertyIgnoralByName(null, beanDescription.classInfo).ignored
        val declaredName = field.declaredName
        if (ignoredProperties.contains(declaredName)) {
            return true
        }

        if (field.declaringType.erasedType.isKotlinElement() &&
            field.type.erasedType == Boolean::class.java &&
            field.name.startsWith("is")
        ) {
            return false
        }

        // @since 4.37.0 also consider overridden property name as it may match the getter method
        val fieldName = field.name

        // other kinds of field ignorals are handled implicitly, i.e. are only available by way of being absent
        return beanDescription.findProperties().stream()
            .noneMatch { propertyDefinition: BeanPropertyDefinition ->
                declaredName == propertyDefinition.internalName ||
                    fieldName == propertyDefinition.internalName
            }
    }
}

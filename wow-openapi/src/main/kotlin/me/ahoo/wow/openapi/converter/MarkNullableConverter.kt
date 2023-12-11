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

package me.ahoo.wow.openapi.converter

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.oas.models.media.Schema
import me.ahoo.wow.openapi.converter.BoundedContextSchemaNameConverter.Companion.getJavaType
import me.ahoo.wow.serialization.JsonSerializer

class MarkNullableConverter : ModelConverter {

    override fun resolve(
        type: AnnotatedType,
        context: ModelConverterContext,
        chain: Iterator<ModelConverter>
    ): Schema<*>? {
        if (!chain.hasNext()) {
            return null
        }
        val schema = chain.next().resolve(type, context, chain) ?: return null
        val properties = context.getRawProperties(schema, type.name) ?: return schema
        val javaType = type.getJavaType() ?: return schema
        val beanDescription = JsonSerializer.serializationConfig.introspect(javaType)
        val beanProperties = beanDescription.findProperties()

        javaType.rawClass.kotlin.members.forEach { member ->
            val beanProperty = beanProperties.firstOrNull {
                it.internalName == member.name
            } ?: return@forEach
            val propertySchema = properties[beanProperty.name] ?: return@forEach
            if (member.returnType.isMarkedNullable) {
                propertySchema.nullable(true)
                if (propertySchema.`$ref` != null) {
                    val refSchema = Schema<Any>().`$ref`(propertySchema.`$ref`)
                    propertySchema.allOf(listOf(refSchema))
                    propertySchema.`$ref`(null)
                }
            }
        }

        return schema
    }

    private fun ModelConverterContext.getRawProperties(
        scheme: Schema<*>,
        schemaName: String?
    ): Map<String, Schema<*>>? {
        if (scheme.properties != null) {
            return scheme.properties
        }
        if (schemaName.isNullOrBlank()) {
            return null
        }
        return definedModels[schemaName]?.properties
    }
}

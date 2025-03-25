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

package me.ahoo.wow.schema.kotlin

import com.fasterxml.classmate.ResolvedType
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.SchemaKeyword
import me.ahoo.wow.schema.JsonSchema.Companion.asCustomDefinition
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema
import me.ahoo.wow.schema.JsonSchema.Companion.toPropertyName
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaType

object KotlinCustomDefinitionProvider : CustomDefinitionProviderV2 {
    override fun provideCustomSchemaDefinition(
        javaType: ResolvedType,
        context: SchemaGenerationContext
    ): CustomDefinition? {
        javaType.erasedType.getAnnotation(Metadata::class.java) ?: return null
        if (javaType.erasedType.isEnum ||
            javaType.erasedType.packageName.startsWith("kotlin") ||
            javaType.erasedType.packageName.startsWith("kotlinx")
        ) {
            return null
        }

        val kotlinGettersIfNonFields = javaType.erasedType.kotlin.memberProperties.filter {
            it.visibility == KVisibility.PUBLIC && it.javaField == null
        }
        if (kotlinGettersIfNonFields.isEmpty()) {
            return null
        }
        val rootSchema = context.createStandardDefinition(javaType, this).asJsonSchema()
        val propertiesNode: ObjectNode = (rootSchema.getProperties()).let {
            it ?: context.generatorConfig.createObjectNode().also { node ->
                rootSchema.set(SchemaKeyword.TAG_PROPERTIES, node)
            }
        }
        for (kotlinGetter in kotlinGettersIfNonFields) {
            if (propertiesNode.get(kotlinGetter.name) == null) {
                val returnType = context.typeContext.resolve(kotlinGetter.returnType.javaType)
                val getterNode = context.createDefinition(returnType)
                val readOnly = SchemaKeyword.TAG_READ_ONLY.toPropertyName()
                getterNode.put(readOnly, true)
                propertiesNode.set<ObjectNode>(kotlinGetter.name, getterNode)
            }
        }
        return rootSchema.asCustomDefinition()
    }
}

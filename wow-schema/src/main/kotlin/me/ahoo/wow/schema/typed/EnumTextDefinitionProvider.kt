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

package me.ahoo.wow.schema.typed

import com.fasterxml.classmate.ResolvedType
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import me.ahoo.wow.models.common.EnumText
import me.ahoo.wow.schema.JsonSchema.Companion.asCustomDefinition
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema

object EnumTextDefinitionProvider : CustomDefinitionProviderV2 {
    const val ENUM_TEXT_KEY_NAME = "x-enum-text"
    override fun provideCustomSchemaDefinition(
        javaType: ResolvedType,
        context: SchemaGenerationContext
    ): CustomDefinition? {
        if (!javaType.isInstanceOf(EnumText::class.java) || !javaType.erasedType.isEnum) {
            return null
        }
        val rootSchema = context.createStandardDefinition(javaType, this).asJsonSchema()
        val enumTextsNode = context.generatorConfig.createObjectNode()
        javaType.erasedType.enumConstants.forEach {
            it as Enum<*>
            it as EnumText
            enumTextsNode.put(it.name, it.text)
        }
        rootSchema.set(ENUM_TEXT_KEY_NAME, enumTextsNode)
        return rootSchema.asCustomDefinition()
    }
}

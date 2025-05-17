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
import com.github.victools.jsonschema.generator.SchemaKeyword
import me.ahoo.wow.schema.AggregatedFieldPaths.commandAggregatedFieldPaths
import me.ahoo.wow.schema.JsonSchema.Companion.toPropertyName

object AggregatedFieldsDefinitionProvider : CustomDefinitionProviderV2 {
    private val type: Class<*> = AggregatedFields::class.java

    override fun provideCustomSchemaDefinition(
        javaType: ResolvedType,
        context: SchemaGenerationContext
    ): CustomDefinition? {
        if (!javaType.isInstanceOf(type)) {
            return null
        }

        val schemaVersion = context.generatorConfig.schemaVersion
        val rootNode = context.generatorConfig.createObjectNode()
        rootNode.put(
            SchemaKeyword.TAG_TYPE.toPropertyName(schemaVersion),
            SchemaKeyword.TAG_TYPE_STRING.toPropertyName(schemaVersion)
        )
        val commandAggregateType = javaType.typeBindings.getBoundType(0).erasedType
        if (commandAggregateType == Any::class.java) {
            return CustomDefinition(rootNode)
        }
        val enumValues = commandAggregateType.kotlin.commandAggregatedFieldPaths()
        rootNode.putPOJO(SchemaKeyword.TAG_ENUM.toPropertyName(schemaVersion), enumValues)

        return CustomDefinition(rootNode)
    }
}

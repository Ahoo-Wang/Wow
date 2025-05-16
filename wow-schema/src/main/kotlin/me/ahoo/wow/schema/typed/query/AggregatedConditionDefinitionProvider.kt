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

package me.ahoo.wow.schema.typed.query

import com.fasterxml.classmate.ResolvedType
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ICondition
import me.ahoo.wow.schema.JsonSchema.Companion.asCustomDefinition
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema
import me.ahoo.wow.schema.typed.AggregatedFields

object AggregatedConditionDefinitionProvider : CustomDefinitionProviderV2 {
    private val type: Class<*> = AggregatedCondition::class.java

    override fun provideCustomSchemaDefinition(
        javaType: ResolvedType,
        context: SchemaGenerationContext
    ): CustomDefinition? {
        if (!javaType.isInstanceOf(type)) {
            return null
        }
        val schemaVersion = context.generatorConfig.schemaVersion
        val conditionType = context.typeContext.resolve(Condition::class.java)
        val rootSchema = context.createStandardDefinition(conditionType, this).asJsonSchema(schemaVersion)
        if (javaType.typeBindings.isEmpty) {
            return rootSchema.asCustomDefinition()
        }
        val commandAggregateType = javaType.typeBindings.getBoundType(0).erasedType

        val aggregatedFieldsType =
            context.typeContext.resolve(AggregatedFields::class.java, commandAggregateType)
        val aggregatedFieldsNode = context.createDefinitionReference(aggregatedFieldsType)
        rootSchema.requiredGetProperties().set<ObjectNode>(ICondition::field.name, aggregatedFieldsNode)

        return rootSchema.asCustomDefinition()
    }
}

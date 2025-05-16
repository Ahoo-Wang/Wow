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
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.schema.AggregatedFieldPaths.allFieldPaths
import me.ahoo.wow.schema.JsonSchema.Companion.asCustomDefinition
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema
import me.ahoo.wow.schema.JsonSchema.Companion.toPropertyName
import me.ahoo.wow.serialization.state.StateAggregateRecords

object AggregatedFieldsDefinitionProvider : CustomDefinitionProviderV2 {
    private val type: Class<*> = AggregatedFields::class.java

    override fun provideCustomSchemaDefinition(
        javaType: ResolvedType,
        context: SchemaGenerationContext
    ): CustomDefinition? {
        if (!javaType.isInstanceOf(type)) {
            return null
        }

        if (javaType.typeBindings.isEmpty) {
            return null
        }

        val schemaVersion = context.generatorConfig.schemaVersion
        val rootSchema = context.generatorConfig.createObjectNode().asJsonSchema(schemaVersion = schemaVersion)
        val propertiesNode = rootSchema.set(
            SchemaKeyword.TAG_PROPERTIES,
            context.generatorConfig.createObjectNode()
        ).requiredGetProperties()
        val typeNode = propertiesNode.putObject(SchemaKeyword.TAG_TYPE.toPropertyName(schemaVersion))
        val commandAggregateType = javaType.typeBindings.getBoundType(0).erasedType
        val aggregateMetadata = commandAggregateType.aggregateMetadata<Any, Any>()
        val stateAggregateType = aggregateMetadata.state.aggregateType.kotlin
        val enumValues = stateAggregateType.allFieldPaths(
            parentName = StateAggregateRecords.STATE,
            fields = listOf(
                StateAggregateRecords.EVENT_ID,
                StateAggregateRecords.FIRST_OPERATOR,
                StateAggregateRecords.OPERATOR,
                StateAggregateRecords.FIRST_EVENT_TIME,
                StateAggregateRecords.EVENT_TIME
            )
        )
        typeNode.putPOJO(SchemaKeyword.TAG_ENUM.toPropertyName(schemaVersion), enumValues)

        return rootSchema.asCustomDefinition()
    }
}

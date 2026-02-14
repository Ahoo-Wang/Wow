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
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import me.ahoo.wow.api.query.ConditionCapable
import me.ahoo.wow.schema.JsonSchema.Companion.asCustomDefinition
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema

abstract class AggregatedConditionCapableDefinitionProvider : CustomDefinitionProviderV2 {
    abstract val queryType: Class<*>
    abstract val aggregatedType: Class<*>
    override fun provideCustomSchemaDefinition(
        javaType: ResolvedType,
        context: SchemaGenerationContext
    ): CustomDefinition? {
        if (!javaType.isInstanceOf(aggregatedType)) {
            return null
        }
        val schemaVersion = context.generatorConfig.schemaVersion
        val queryType = context.typeContext.resolve(queryType)
        val rootSchema = context.createStandardDefinition(queryType, this).asJsonSchema(schemaVersion)
        if (javaType.typeBindings.isEmpty) {
            return rootSchema.asCustomDefinition()
        }
        val commandAggregateType = javaType.typeBindings.getBoundType(0).erasedType
        val conditionType =
            context.typeContext.resolve(AggregatedCondition::class.java, commandAggregateType)
        val aggregateConditionNode = context.createDefinitionReference(conditionType)
        rootSchema.requiredGetProperties().set(ConditionCapable<*>::condition.name, aggregateConditionNode)
        return rootSchema.asCustomDefinition()
    }
}

object AggregatedListQueryDefinitionProvider : AggregatedConditionCapableDefinitionProvider() {
    override val queryType: Class<*> = me.ahoo.wow.api.query.ListQuery::class.java
    override val aggregatedType: Class<*> = AggregatedListQuery::class.java
}

object AggregatedPagedQueryDefinitionProvider : AggregatedConditionCapableDefinitionProvider() {
    override val queryType: Class<*> = me.ahoo.wow.api.query.PagedQuery::class.java
    override val aggregatedType: Class<*> = AggregatedPagedQuery::class.java
}

object AggregatedSingleQueryDefinitionProvider : AggregatedConditionCapableDefinitionProvider() {
    override val queryType: Class<*> = me.ahoo.wow.api.query.SingleQuery::class.java
    override val aggregatedType: Class<*> = AggregatedSingleQuery::class.java
}

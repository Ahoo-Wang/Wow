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

import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import me.ahoo.wow.api.query.ConditionCapable
import me.ahoo.wow.schema.JsonSchema
import me.ahoo.wow.schema.JsonSchema.Companion.asCustomDefinition

abstract class AggregatedConditionCapableDefinitionProvider : AbstractAggregatedQueryDefinitionProvider() {

    override fun createCustomDefinition(
        rootSchema: JsonSchema,
        commandAggregateType: Class<*>,
        context: SchemaGenerationContext
    ): CustomDefinition {
        val conditionType =
            context.typeContext.resolve(AggregatedCondition::class.java, commandAggregateType)
        val aggregateConditionNode = context.createDefinitionReference(conditionType)
        rootSchema.requiredGetProperties().set<ObjectNode>(ConditionCapable<*>::condition.name, aggregateConditionNode)
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

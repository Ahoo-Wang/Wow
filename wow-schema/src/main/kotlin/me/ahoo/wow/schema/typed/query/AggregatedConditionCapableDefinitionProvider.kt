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

@file:Suppress("DEPRECATION")

package me.ahoo.wow.schema.typed.query

import com.fasterxml.classmate.ResolvedType
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ConditionCapable
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.schema.JsonSchema.Companion.asCustomDefinition
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema

@Deprecated("Use FilterExpressionDefinitionProvider.")
abstract class AggregatedConditionCapableDefinitionProvider : CustomDefinitionProviderV2 {
    abstract val queryType: Class<*>
    abstract val aggregatedType: Class<*>

    override fun provideCustomSchemaDefinition(
        javaType: ResolvedType,
        context: SchemaGenerationContext,
    ): CustomDefinition? {
        if (!javaType.isInstanceOf(aggregatedType)) return null
        val schemaVersion = context.generatorConfig.schemaVersion
        val rootSchema = context.createStandardDefinition(
            context.typeContext.resolve(queryType),
            this,
        ).asJsonSchema(schemaVersion)
        if (javaType.typeBindings.isEmpty) return rootSchema.asCustomDefinition()

        val aggregateType = javaType.typeBindings.getBoundType(0).erasedType
        val conditionType = context.typeContext.resolve(AggregatedCondition::class.java, aggregateType)
        val properties = rootSchema.requiredGetProperties()
        properties.set(
            ConditionCapable<*>::condition.name,
            context.createDefinitionReference(conditionType),
        )
        return rootSchema.asCustomDefinition()
    }
}

@Deprecated("Use FilterExpressionDefinitionProvider.")
object AggregatedListQueryDefinitionProvider : AggregatedConditionCapableDefinitionProvider() {
    override val queryType: Class<*> = LegacyListQuerySchema::class.java
    override val aggregatedType: Class<*> = AggregatedListQuery::class.java
}

@Deprecated("Use FilterExpressionDefinitionProvider.")
object AggregatedPagedQueryDefinitionProvider : AggregatedConditionCapableDefinitionProvider() {
    override val queryType: Class<*> = LegacyPagedQuerySchema::class.java
    override val aggregatedType: Class<*> = AggregatedPagedQuery::class.java
}

@Deprecated("Use FilterExpressionDefinitionProvider.")
object AggregatedSingleQueryDefinitionProvider : AggregatedConditionCapableDefinitionProvider() {
    override val queryType: Class<*> = LegacySingleQuerySchema::class.java
    override val aggregatedType: Class<*> = AggregatedSingleQuery::class.java
}

private data class LegacyListQuerySchema(
    val condition: Condition = Condition.ALL,
    val projection: Projection = Projection.ALL,
    val sort: List<Sort> = emptyList(),
    val limit: Int = 0,
)

private data class LegacyPagedQuerySchema(
    val condition: Condition = Condition.ALL,
    val projection: Projection = Projection.ALL,
    val sort: List<Sort> = emptyList(),
    val pagination: Pagination = Pagination.DEFAULT,
)

private data class LegacySingleQuerySchema(
    val condition: Condition = Condition.ALL,
    val projection: Projection = Projection.ALL,
    val sort: List<Sort> = emptyList(),
)

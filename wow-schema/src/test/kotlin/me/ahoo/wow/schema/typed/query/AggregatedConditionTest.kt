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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import me.ahoo.wow.schema.TestAggregate
import me.ahoo.wow.schema.typed.AggregatedFields
import org.junit.jupiter.api.Test

class AggregatedConditionTest {

    @Test
    fun `should construct aggregated condition`() {
        val condition = AggregatedCondition(
            field = object : AggregatedFields<TestAggregate> {},
            operator = Operator.EQ,
            value = "test",
            children = emptyList(),
        )
        condition.assert().isNotNull()
    }

    @Test
    fun `should generate aggregated condition schema for test aggregate`() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder().build()
        val schema = jsonSchemaGenerator.generateSchema(
            AggregatedCondition::class.java,
            TestAggregate::class.java
        ).asJsonSchema()
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun `should generate aggregated list query schema for test aggregate`() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder().build()
        val schema = jsonSchemaGenerator.generateSchema(
            AggregatedListQuery::class.java,
            TestAggregate::class.java
        ).asJsonSchema()
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun `should generate aggregated paged query schema for test aggregate`() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder().build()
        val schema = jsonSchemaGenerator.generateSchema(
            AggregatedPagedQuery::class.java,
            TestAggregate::class.java
        ).asJsonSchema()
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun `should generate aggregated single query schema for test aggregate`() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder().build()
        val schema = jsonSchemaGenerator.generateSchema(
            AggregatedSingleQuery::class.java,
            TestAggregate::class.java
        ).asJsonSchema()
        schema.getProperties().assert().isNotNull()
    }
}

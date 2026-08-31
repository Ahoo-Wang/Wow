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

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.schema.WowSchemaLoader
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class FilterExpressionDefinitionProviderTest {
    @Test
    fun `filter schema should publish search modes`() {
        val schemaDocument = WowSchemaLoader.load(FilterExpression::class.java)
        val mode = schemaDocument.path("definitions").path("search").path("properties").path("mode")

        mode.path("enum").toList().map { it.stringValue() }.assert().containsExactly("TERMS", "PHRASE")
        mode.path("default").stringValue().assert().isEqualTo("TERMS")

        val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7).getSchema(schemaDocument)
        val mapper = JsonMapper.builder().build()
        schema.validate(mapper.readTree("""{"op":"SEARCH","query":"event sourcing"}"""))
            .assert().isEmpty()
        schema.validate(
            mapper.readTree("""{"op":"SEARCH","query":"event sourcing","mode":"PHRASE"}"""),
        ).assert().isEmpty()
        schema.validate(
            mapper.readTree("""{"op":"SEARCH","query":"event \"sourcing\"","mode":"PHRASE"}"""),
        ).assert().isEmpty()
    }

    @Test
    fun `filter schema should publish metadata operators`() {
        val schema = WowSchemaLoader.load(FilterExpression::class.java)
        val definitions = schema.path("definitions")
        val references = definitions.path("filterExpression").path("oneOf").toList()
            .map { it.path("\$ref").stringValue() }

        listOf("id", "ids", "aggregateId", "aggregateIds", "tenantId", "ownerId", "spaceId")
            .forEach { references.assert().contains("#/definitions/$it") }
        definitions.path("ids").path("properties").path("values").path("minItems").intValue()
            .assert().isOne()
        definitions.path("aggregateIds").path("properties").path("values").path("minItems").intValue()
            .assert().isOne()
    }

    @Test
    fun `filter schema should accept operand free empty string operators`() {
        val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7)
            .getSchema(WowSchemaLoader.load(FilterExpression::class.java))
        val mapper = JsonMapper.builder().build()

        listOf("IS_EMPTY_STRING", "IS_NOT_EMPTY_STRING").forEach { operator ->
            schema.validate(mapper.readTree("""{"op":"$operator","field":"state.name"}"""))
                .assert().isEmpty()
            schema.validate(mapper.readTree("""{"op":"$operator","field":"state.name","value":""}"""))
                .assert().isNotEmpty()
        }
    }

    @Test
    fun `filter schema should accept at-prefixed logical fields`() {
        val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7)
            .getSchema(WowSchemaLoader.load(FilterExpression::class.java))
        val filter = JsonMapper.builder().build().readTree(
            """{"op":"EQ","field":"@timestamp","value":"now"}""",
        )

        schema.validate(filter).assert().isEmpty()
    }

    @Test
    fun `filter schema should publish extended relative calendar operators`() {
        val schemaDocument = WowSchemaLoader.load(FilterExpression::class.java)
        val definitions = schemaDocument.path("definitions")
        val expected = linkedMapOf(
            "yesterday" to "YESTERDAY",
            "nextMonth" to "NEXT_MONTH",
            "lastYear" to "LAST_YEAR",
            "thisYear" to "THIS_YEAR",
            "nextYear" to "NEXT_YEAR",
        )

        listOf("filterExpression", "elementPredicate").forEach { unionName ->
            val references = definitions.path(unionName).path("oneOf").toList()
                .map { it.path("\$ref").stringValue() }
            expected.keys.forEach { name -> references.assert().contains("#/definitions/$name") }
        }

        val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7)
            .getSchema(schemaDocument)
        val mapper = JsonMapper.builder().build()
        expected.values.forEach { operator ->
            val filter = mapper.readTree(
                """{"op":"$operator","field":"state.createdAt","zoneId":"UTC","datePattern":"yyyy-MM-dd"}""",
            )
            schema.validate(filter).assert().isEmpty()
        }
    }
}

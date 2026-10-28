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

package me.ahoo.wow.elasticsearch.query

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.store.QueryIndexReport
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory

class ElasticsearchPayloadTest {
    private fun scalar(type: QueryValueType, description: String? = null) =
        QueryValueSchema(QueryValueKind.SCALAR, description = description, valueTypes = setOf(type))

    private fun obj(vararg properties: Pair<String, QueryValueSchema>) =
        QueryValueSchema(QueryValueKind.OBJECT, properties = properties.toMap())

    private fun array(items: QueryValueSchema) = QueryValueSchema(QueryValueKind.ARRAY, items = items)

    private fun union(vararg values: QueryValueSchema) =
        QueryValueSchema(QueryValueKind.UNION, alternatives = values.toList())

    private val nothing = QueryValueSchema(QueryValueKind.NULL)

    @Test
    fun `a single alternative maps as it is, merged or not`() {
        val string = scalar(QueryValueType.STRING)
        union(string, nothing).mappable(false).assert().isSameAs(string)
        union(string, nothing).mappable(true).assert().isSameAs(string)
        nothing.mappable(true).assert().isNull()
        union(string, scalar(QueryValueType.STRING)).mappable(false).assert().isNull()
    }

    @Test
    fun `objects merge their properties, a property of several values becoming their union`() {
        val amount = scalar(QueryValueType.INTEGER, "a")
        val merged = union(
            obj("amount" to amount, "sku" to scalar(QueryValueType.STRING)),
            obj("amount" to scalar(QueryValueType.INTEGER, "b")),
        ).mappable(true)!!
        merged.kind.assert().isEqualTo(QueryValueKind.OBJECT)
        merged.properties.keys.assert().containsExactly("amount", "sku")
        merged.properties.getValue("amount").kind.assert().isEqualTo(QueryValueKind.UNION)
        merged.properties.getValue("amount").mappable(true).assert().isSameAs(amount)
    }

    @Test
    fun `scalars merge only when of one type and meaning`() {
        val first = scalar(QueryValueType.STRING, "a")
        union(first, scalar(QueryValueType.STRING, "b")).mappable(true).assert().isSameAs(first)
        union(first, scalar(QueryValueType.INTEGER)).mappable(true).assert().isNull()
        val date = QueryValueSchema(
            QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.STRING),
            semanticType = Temporal.Date,
        )
        union(first, date).mappable(true).assert().isNull()
    }

    @Test
    fun `arrays merge their items, and kinds that differ do not merge`() {
        val merged = union(
            array(scalar(QueryValueType.STRING, "a")),
            array(scalar(QueryValueType.STRING, "b")),
        ).mappable(true)!!
        merged.kind.assert().isEqualTo(QueryValueKind.ARRAY)
        merged.items!!.mappable(true)!!.valueTypes.assert().containsExactly(QueryValueType.STRING)
        union(array(scalar(QueryValueType.STRING)), scalar(QueryValueType.STRING)).mappable(true).assert().isNull()
        union(
            obj(),
            QueryValueSchema(QueryValueKind.OBJECT, additionalProperties = scalar(QueryValueType.STRING)),
        ).mappable(true).assert().isNull()
    }

    @Test
    fun `each payload says where it lies and how its fields are added`() {
        val properties = JsonNodeFactory.instance.objectNode().put("marker", true)
        val state = ElasticsearchPayload.Root("state")
        state.path.assert().isEqualTo("state")
        state.mergesVariants.assert().isFalse()
        state.mapping(properties).path("properties").path("state").path("marker").booleanValue().assert().isTrue()
        val bodies = ElasticsearchPayload.EventBodies
        bodies.path.assert().isEqualTo("events.body")
        bodies.mergesVariants.assert().isTrue()
        val events = bodies.mapping(properties).path("properties").path("events")
        events.path("type").stringValue().assert().isEqualTo("nested")
        events.path("properties").path("body").path("marker").booleanValue().assert().isTrue()
    }

    @Test
    fun `a schema without the payload maps nothing`(): Unit = runBlocking {
        val schema = LogicalQuerySchema(obj("version" to scalar(QueryValueType.INTEGER)))
        ElasticsearchPayload.Root("state").value(schema).assert().isNull()
        ElasticsearchPayload.EventBodies.value(schema).assert().isNull()
        ElasticsearchStateMappings(mockk(), mockk(), ElasticsearchPayload.EventBodies).manage("index", schema)
            .assert().isEqualTo(QueryIndexReport.NONE)
    }
}

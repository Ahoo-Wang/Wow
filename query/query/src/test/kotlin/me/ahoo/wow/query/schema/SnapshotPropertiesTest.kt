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

package me.ahoo.wow.query.schema

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SnapshotProperties
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.tck.QueryBackendSpec
import org.junit.jupiter.api.Test

/**
 * `SnapshotProperties` (`wow-query-api`, the base of every generated `<State>Properties`) names exactly the fields
 * the snapshot query model declares (decisions D128, D163): every system field, each operator's properties and the
 * state, and nothing the model would refuse.
 */
class SnapshotPropertiesTest {
    private val properties: List<QueryField> = SnapshotProperties::class.java.declaredFields
        .filter { it.type == QueryField::class.java }
        .map { it.apply { isAccessible = true }.get(SnapshotProperties) as QueryField }

    @Test
    fun `the top-level properties are the model's system fields`() {
        properties.filterNot { '.' in it.path }.assert()
            .containsExactlyInAnyOrderElementsOf(SnapshotQueryModelProfile.systemDeclaration.fields.keys)
    }

    @Test
    fun `the operator properties are the model's operator fields`() {
        val operatorFields = listOf(OperatorFields.ID, OperatorFields.TENANT_ID, OperatorFields.CROSS_SCOPE)
        val expected = listOf(SnapshotFields.OPERATOR, SnapshotFields.FIRST_OPERATOR).flatMap { operator ->
            operatorFields.map { QueryField("$operator.$it") }
        }
        properties.filter { '.' in it.path }.assert().containsExactlyInAnyOrderElementsOf(expected)
    }

    @Test
    fun `every property and a tag resolve in a compiled snapshot schema`() {
        val type = AggregateType("properties", "order")
        val context = QuerySchemaContext(type, QueryModel.SNAPSHOT)
        val schema = runBlocking {
            DefaultQueryModelSchemaProvider(
            context,
                listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, QueryBackendSpec.DECLARATION)))),
                InMemoryQueryStorage().create(type).storage,
            ).schema()
        }
        (properties + SnapshotProperties.tag("region")).filter { schema.field(it) == null }.assert().isEmpty()
    }
}

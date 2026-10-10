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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.tck.mock.MockCommandAggregate
import org.junit.jupiter.api.Test

class DeclaredFieldsTest {
    private val inferred = querySchemaRegistration(MockCommandAggregate::class, QueryModel.SNAPSHOT) {
        field("state.name") { types(QueryValueType.STRING) }
        field("state.config") { description("Config") }
    }

    /** A higher-priority declaration that opens `state.config`, as the view store's does. */
    private val declared = querySchemaRegistration(MockCommandAggregate::class, QueryModel.SNAPSHOT) {
        field("state.config") {
            kind(QueryValueKind.OBJECT)
            property("kind") { types(QueryValueType.STRING) }
            property("panels") {
                items { property("viewId") { types(QueryValueType.STRING) } }
            }
            property("labels") {
                kind(QueryValueKind.OBJECT)
                values { types(QueryValueType.STRING) }
            }
        }
    }

    private fun source(priority: Int, registration: QuerySchemaRegistration) = object : QuerySchemaSource {
        override val priority: Int = priority
        override fun load(context: QuerySchemaContext) =
            BeanQuerySchemaSource(listOf(registration)).load(context)
    }

    @Test
    fun `lists the fields every source declares, merged as the Catalog merges them`() {
        val fields = inferred.context.declaredFields(
            listOf(
                source(QuerySchemaSourcePriority.INFERRED, inferred),
                source(QuerySchemaSourcePriority.BEAN, declared),
            ),
        ).block()!!

        fields.assert().contains(
            QueryField("state.name"),
            QueryField("state.config"),
            QueryField("state.config.kind"),
            QueryField("state.config.panels"),
            QueryField("state.config.panels.viewId"),
            QueryField("state.config.labels"),
        )
        // Map entries are named by the caller's keys, so no fixed path names them.
        fields.map(QueryField::path).filter { it.startsWith("state.config.labels.") }.assert().isEmpty()
        // The system fields come first, whatever the sources declare.
        fields.assert().contains(QueryField("aggregateId"), QueryField("state"))
    }

    @Test
    fun `lists only the system and inferred fields without declarations`() {
        val fields = inferred.context.declaredFields(listOf(source(QuerySchemaSourcePriority.INFERRED, inferred)))
            .block()!!

        fields.assert().contains(QueryField("state.name"), QueryField("state.config"))
        fields.assert().doesNotContain(QueryField("state.config.kind"))
    }
}

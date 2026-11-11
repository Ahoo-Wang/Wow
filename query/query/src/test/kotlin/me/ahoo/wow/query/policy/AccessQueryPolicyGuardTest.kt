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

package me.ahoo.wow.query.policy

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.policy.AccessFixtures.DOCUMENT
import me.ahoo.wow.query.policy.AccessFixtures.MANIFEST
import me.ahoo.wow.query.policy.AccessFixtures.caller
import me.ahoo.wow.query.policy.AccessFixtures.documents
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.ManifestQuerySchemaSource
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QuerySchemaContext
import org.junit.jupiter.api.Test

/**
 * Where a policy's filter checks that the attributes are recorded: on an events query, whose stream may not be stamped
 * yet, and never on a snapshot query, whose snapshot always holds them (`{}` for none). A store that indexes nothing for
 * an empty object, Elasticsearch, would read that check as false and hide a snapshot whose attributes are all empty.
 */
class AccessQueryPolicyGuardTest {
    private val policy = AccessQueryPolicy(
        AccessPolicies.of(MANIFEST, setOf(DOCUMENT.context), listOf(documents), AccessFixtures::aggregatesOf),
    )
    private val recorded = IsNotNullFilter(QueryField("access"))

    private fun filterOf(model: QueryModel): FilterExpression = runBlocking {
        val schema = DefaultQueryModelSchemaProvider(
            QuerySchemaContext(DOCUMENT, model),
            listOf(ManifestQuerySchemaSource(MANIFEST)),
            InMemoryQueryStorage().binding(DOCUMENT).storage,
        ).schema()
        val context = QueryContext(ListQuery(MatchAllFilter), DOCUMENT, schema, QueryType.LIST, QueryEntry.HTTP)
        policy.evaluate(caller("alice", "departments" to listOf("sales")), context)
    }

    private fun FilterExpression.operands(): List<FilterExpression> = (this as? AndFilter)?.operands ?: listOf(this)

    @Test
    fun `a snapshot query's filter has no check that the attributes are recorded`() {
        filterOf(QueryModel.SNAPSHOT).operands().assert().doesNotContain(recorded)
    }

    @Test
    fun `an events query's filter keeps the check, a stream not stamped never read`() {
        filterOf(QueryModel.EVENTS).operands().assert().contains(recorded)
    }
}

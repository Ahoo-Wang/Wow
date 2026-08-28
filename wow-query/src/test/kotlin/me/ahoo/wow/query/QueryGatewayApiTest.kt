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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.filter.Handler
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class QueryGatewayApiTest {
    @Test
    fun `gateway should not expose filter handler contract`() {
        Handler::class.java.isAssignableFrom(QueryGateway::class.java).assert().isFalse()
    }

    @Test
    fun `gateway should expose only filter expression count`() {
        QueryGateway::class.java.methods.any { method ->
            method.name == "count" && method.parameterTypes.contentEquals(
                arrayOf(NamedAggregate::class.java, Condition::class.java),
            )
        }.assert().isFalse()
    }

    @Test
    fun `gateway aggregation should be mandatory`() {
        val aggregate = QueryGateway::class.java.getMethod(
            "aggregate",
            NamedAggregate::class.java,
            AggregationQuery::class.java,
        )

        Modifier.isAbstract(aggregate.modifiers).assert().isTrue()
    }
}

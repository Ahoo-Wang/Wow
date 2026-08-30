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
import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.filter.Handler
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.lang.reflect.Modifier

class QueryGatewayApiTest {
    @Test
    fun `gateway should be aggregate bound without exposing handler contract`() {
        NamedAggregateDecorator::class.java.isAssignableFrom(QueryGateway::class.java).assert().isTrue()
        Handler::class.java.isAssignableFrom(QueryGateway::class.java).assert().isFalse()
        QueryGateway::class.java.declaredMethods.filter { Modifier.isAbstract(it.modifiers) }
            .flatMap { it.parameterTypes.asIterable() }
            .none { it == NamedAggregate::class.java }
            .assert().isTrue()
    }

    @Test
    fun `gateway should expose the five object-node operations and typed variants`() {
        QueryGateway::class.java.getMethod(
            "single",
            ISingleQuery::class.java
        ).returnType.assert().isEqualTo(Mono::class.java)
        QueryGateway::class.java.getMethod("dynamicSingle", ISingleQuery::class.java).returnType
            .assert().isEqualTo(Mono::class.java)
        QueryGateway::class.java.getMethod(
            "list",
            IListQuery::class.java
        ).returnType.assert().isEqualTo(Flux::class.java)
        QueryGateway::class.java.getMethod("dynamicList", IListQuery::class.java).returnType
            .assert().isEqualTo(Flux::class.java)
        QueryGateway::class.java.getMethod(
            "paged",
            IPagedQuery::class.java
        ).returnType.assert().isEqualTo(Mono::class.java)
        QueryGateway::class.java.getMethod("dynamicPaged", IPagedQuery::class.java).returnType
            .assert().isEqualTo(Mono::class.java)
        QueryGateway::class.java.getMethod("cursor", ICursorQuery::class.java).returnType
            .assert().isEqualTo(Mono::class.java)
        QueryGateway::class.java.getMethod("dynamicCursor", ICursorQuery::class.java).returnType
            .assert().isEqualTo(Mono::class.java)
        QueryGateway::class.java.getMethod("count", FilterExpression::class.java).returnType
            .assert().isEqualTo(Mono::class.java)
        QueryGateway::class.java.getMethod("aggregate", AggregationQuery::class.java).returnType
            .assert().isEqualTo(Flux::class.java)
    }
}

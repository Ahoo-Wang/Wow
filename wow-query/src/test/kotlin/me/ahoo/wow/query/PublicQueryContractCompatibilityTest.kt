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
import me.ahoo.wow.query.filter.QueryType
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class PublicQueryContractCompatibilityTest {

    @Test
    fun `query service should retain seven public generic signatures`() {
        QueryService::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { method ->
                val parameters = method.genericParameterTypes.joinToString(",") { it.typeName }
                "${method.name}($parameters):${method.genericReturnType.typeName}"
            }
            .sorted()
            .assert()
            .containsExactly(
                "count(me.ahoo.wow.api.query.Condition):reactor.core.publisher.Mono<java.lang.Long>",
                "dynamicList(me.ahoo.wow.api.query.IListQuery):reactor.core.publisher.Flux<me.ahoo.wow.api.query.DynamicDocument>",
                "dynamicPaged(me.ahoo.wow.api.query.IPagedQuery):reactor.core.publisher.Mono<me.ahoo.wow.api.query.PagedList<me.ahoo.wow.api.query.DynamicDocument>>",
                "dynamicSingle(me.ahoo.wow.api.query.ISingleQuery):reactor.core.publisher.Mono<me.ahoo.wow.api.query.DynamicDocument>",
                "list(me.ahoo.wow.api.query.IListQuery):reactor.core.publisher.Flux<R>",
                "paged(me.ahoo.wow.api.query.IPagedQuery):reactor.core.publisher.Mono<me.ahoo.wow.api.query.PagedList<R>>",
                "single(me.ahoo.wow.api.query.ISingleQuery):reactor.core.publisher.Mono<R>",
            )

        QueryService::class.java.genericInterfaces.map { it.typeName }.assert().containsExactly(
            "me.ahoo.wow.api.modeling.NamedAggregateDecorator",
        )
    }

    @Test
    fun `query type should retain seven values`() {
        QueryType.entries.map { it.name }.assert().containsExactly(
            "SINGLE",
            "DYNAMIC_SINGLE",
            "LIST",
            "DYNAMIC_LIST",
            "PAGED",
            "DYNAMIC_PAGED",
            "COUNT",
        )
    }
}

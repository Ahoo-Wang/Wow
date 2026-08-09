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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.gateway.GatewayEventStreamQueryServiceFactory
import me.ahoo.wow.query.gateway.GatewaySnapshotQueryServiceFactory
import me.ahoo.wow.query.gateway.QueryGatewayRuntime
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

    @Test
    fun `query gateway runtime should retain legacy composition factory`() {
        QueryGatewayRuntime.Companion::class.java.declaredMethods
            .filter { method -> method.name == "create" && Modifier.isPublic(method.modifiers) }
            .map { method ->
                val parameters = method.parameterTypes.joinToString(",") { type -> type.name }
                "${method.name}($parameters):${method.returnType.name}"
            }.assert()
            .contains(
                "create(java.lang.Iterable," +
                    "me.ahoo.wow.query.gateway.QueryRawServiceSource," +
                    "me.ahoo.wow.query.gateway.QueryLegacyDialectResolver," +
                    "me.ahoo.wow.query.gateway.QueryAuthorityResolver," +
                    "me.ahoo.wow.query.gateway.QueryTrustedContextResolver," +
                    "java.lang.Iterable," +
                    "me.ahoo.wow.query.gateway.QueryGatewayConfiguration," +
                    "java.time.Clock," +
                    "reactor.core.scheduler.Scheduler):" +
                    "me.ahoo.wow.query.gateway.QueryGatewayRuntime",
            )
    }

    @Test
    fun `analytics facade factory should use only its construction-time frozen resolver`() {
        QueryGatewayRuntime::class.java.declaredMethods
            .filter { method ->
                method.name == "analyticsQueryServiceFactory" &&
                    Modifier.isPublic(method.modifiers) &&
                    !method.isSynthetic
            }
            .map { method -> method.parameterCount }
            .assert()
            .containsExactly(0)
    }

    @Test
    fun `trusted authority writer must not be JVM public`() {
        listOf(
            "me.ahoo.wow.query.gateway.QueryGatewayKt",
            "me.ahoo.wow.query.gateway.QueryServiceFacadeKt",
        ).flatMap { className -> Class.forName(className).methods.toList() }
            .map { method -> method.name }
            .filter { methodName -> methodName.contains("withTrustedQueryAuthority") }
            .assert()
            .isEmpty()

        listOf(
            QueryGatewayRuntime::class.java,
            GatewaySnapshotQueryServiceFactory::class.java,
            GatewayEventStreamQueryServiceFactory::class.java,
        ).flatMap { type ->
            val constructorTypes = type.declaredConstructors
                .filter { constructor -> Modifier.isPublic(constructor.modifiers) }
                .flatMap { constructor -> constructor.parameterTypes.toList() }
            val methodTypes = type.declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) }
                .flatMap { method -> method.parameterTypes.toList() + method.returnType }
            constructorTypes + methodTypes
        }.map(Class<*>::getName)
            .filter { typeName -> typeName.startsWith("me.ahoo.wow.query.internal.") }
            .assert()
            .isEmpty()
    }
}

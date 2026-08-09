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

@file:OptIn(
    me.ahoo.wow.query.cursor.ExperimentalQueryCursorApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.analytics.AnalyticsQueryService
import me.ahoo.wow.query.analytics.AnalyticsQueryServiceFactory
import me.ahoo.wow.query.cursor.QueryCursorHmacKey
import me.ahoo.wow.query.cursor.QueryCursorLeaseStore
import me.ahoo.wow.query.gateway.AnalyticsQueryGateway
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class PublicAnalyticsQueryContractTest {
    @Test
    fun `analytics ports should remain additive and operation specific`() {
        AnalyticsQueryGateway::class.java.publicGenericSignatures().assert().containsExactly(
            "analyze(me.ahoo.wow.query.gateway.QueryCall,me.ahoo.wow.api.query.analytics.AnalyticsQuery):" +
                "reactor.core.publisher.Mono<me.ahoo.wow.api.query.analytics.AnalyticsPage>",
        )
        AnalyticsQueryService::class.java.publicGenericSignatures().assert().containsExactly(
            "analyze(me.ahoo.wow.api.query.analytics.AnalyticsQuery):" +
                "reactor.core.publisher.Mono<me.ahoo.wow.api.query.analytics.AnalyticsPage>",
        )
        AnalyticsQueryServiceFactory::class.java.publicGenericSignatures().assert().containsExactly(
            "create(me.ahoo.wow.api.modeling.NamedAggregate):me.ahoo.wow.query.analytics.AnalyticsQueryService",
        )
        QueryService::class.java.declaredMethods.map { it.name }.assert().doesNotContain("analyze")
    }

    @Test
    fun `persistent cursor store should expose create load cas delete and bounded expiry scan`() {
        QueryCursorLeaseStore::class.java.publicGenericSignatures().assert().containsExactly(
            "compareAndDelete(me.ahoo.wow.query.cursor.StoredQueryCursorLease):" +
                "reactor.core.publisher.Mono<java.lang.Boolean>",
            "create(me.ahoo.wow.query.cursor.QueryCursorLeaseEntry):" +
                "reactor.core.publisher.Mono<me.ahoo.wow.query.cursor.QueryCursorLeaseCreateResult>",
            "load(me.ahoo.wow.query.cursor.QueryCursorLeaseId):" +
                "reactor.core.publisher.Mono<me.ahoo.wow.query.cursor.StoredQueryCursorLease>",
            "scanExpired(java.time.Instant,me.ahoo.wow.query.cursor.QueryCursorLeaseId,int):" +
                "reactor.core.publisher.Flux<me.ahoo.wow.query.cursor.StoredQueryCursorLease>",
        )
    }

    @Test
    fun `cursor key should not expose its secret through the supported JVM contract`() {
        QueryCursorHmacKey::class.java.declaredMethods
            .filter { method -> Modifier.isPublic(method.modifiers) && !method.isSynthetic }
            .map { method -> method.name }
            .filter { methodName -> methodName.startsWith("secretCopy") }
            .assert()
            .isEmpty()
    }

    private fun Class<*>.publicGenericSignatures(): List<String> = declaredMethods
        .filter { method -> Modifier.isPublic(method.modifiers) && !method.isSynthetic }
        .map { method ->
            val parameters = method.genericParameterTypes.joinToString(",") { type -> type.typeName }
            "${method.name}($parameters):${method.genericReturnType.typeName}"
        }
        .sorted()
}

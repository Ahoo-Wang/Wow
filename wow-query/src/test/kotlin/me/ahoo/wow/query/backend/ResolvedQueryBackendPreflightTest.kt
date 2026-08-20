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

package me.ahoo.wow.query.backend

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.query.GATEWAY_STATUS
import me.ahoo.wow.query.GATEWAY_TARGET
import me.ahoo.wow.query.QueryGatewayFactory
import me.ahoo.wow.query.gatewayConfiguration
import me.ahoo.wow.query.gatewayDescriptor
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class ResolvedQueryBackendPreflightTest {
    @Test
    fun `gateway context protects target-only resolver from readiness before descriptor validation`() {
        val supported = gatewayDescriptor()
        val backend = RecordingQueryBackend(
            QueryBackendDescriptor(
                backendId = supported.backendId,
                documentKinds = supported.documentKinds,
                planVersions = supported.planVersions,
                portableOperators = emptySet(),
                portableFeatures = supported.portableFeatures,
                stringComparisonModes = supported.stringComparisonModes,
                capabilities = supported.capabilities,
                maxBudget = supported.maxBudget,
            ),
        )
        val expression = PredicateExpression(
            GATEWAY_STATUS,
            PortableOperator.EQ,
            listOf(QueryValue.StringValue("OPEN")),
        )

        val gateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                backend,
                backendResolver = QueryBackendResolver {
                    ResolvedQueryBackend.resolve(backend, QueryBackendRouteIdentity("preflight"))
                },
            ),
        )

        StepVerifier.create(gateway.count(CountQueryRequest(GATEWAY_TARGET, expression)))
            .expectErrorSatisfies { error ->
                (error as QueryException).apply {
                    code.assert().isEqualTo(QueryErrorCode.UNSUPPORTED_CAPABILITY)
                    reason.assert().isEqualTo(QueryErrorReason.CAPABILITY_DENIED)
                }
            }.verify()

        backend.readinessSubscriptions.get().assert().isZero()
    }
}

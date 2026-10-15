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

package me.ahoo.wow.ops.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.ops.api.OpsContext
import me.ahoo.wow.runtime.node.ServiceNode
import org.junit.jupiter.api.Test

/** The ops service as its `application.yaml` configures it, on random ports. */
class OpsServerTest {
    @Test
    fun `the server should run one node of the ops context, and describe its routes`() {
        startOps().use { ops ->
            val node = ops.context.getBean(ServiceNode::class.java)
            node.service.assert().isEqualTo("ops-service")
            node.isReady.assert().isTrue()
            node.hostedContexts.assert().containsExactly(OpsContext.NAME)
            val parked = ops.context.getBean(Manifest::class.java).contexts.single().aggregates.single()
            parked.name.assert().isEqualTo(OpsContext.PARKED)
            // A tenant's resource (decision D189), without lifecycle: it mirrors the owning service's ledger.
            parked.ownership.name.assert().isEqualTo(Ownership.TENANT.name)
            parked.lifecycle.assert().isEmpty()

            val manifest = ops.get("/wow/manifest", Who.OPERATOR).assertOk().json
            manifest["contexts"].values().map { it["name"].asString() }.assert().containsExactly(OpsContext.NAME)
            val paths = ops.get("/wow/openapi", Who.OPERATOR).assertOk().json["paths"].propertyNames().toList()
            paths.assert().contains(
                "/ops/parked/{id}/resume",
                "/ops/parked/{id}/skip",
                "/ops/parked/{id}/mark-recoverable",
                "/ops/parked/{id}/apply-retry-spec",
                "/ops/parked/{id}/state",
            )
            // The memory backend serves no snapshot query by itself: the postgres profile does.
            ops.post(OpsContract.QUERY, """{"kind":"list","filter":{"op":"MATCH_ALL"}}""", Who.OPERATOR)
                .status.assert().isEqualTo(NOT_FOUND)
        }
    }

    @Test
    fun `without the header switch no request is served on its headers' word`() {
        startOps(OpsConfiguration.TRUST_IDENTITY_HEADERS to false).use { ops ->
            ops.context.getBean(me.ahoo.wow.http.caller.CallerResolver::class.java).assert()
                .isSameAs(OpsConfiguration.REFUSED)
            ops.get("/wow/ops/parked", Who.OPERATOR).assertProblem(UNAUTHORIZED, "wow.auth.unauthenticated")
            ops.get("/ops/parked/p-1/state", Who.OPERATOR).assertProblem(UNAUTHORIZED, "wow.auth.unauthenticated")
        }
    }

    private companion object {
        const val NOT_FOUND = 404
        const val UNAUTHORIZED = 401
    }
}

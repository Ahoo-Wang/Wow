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

package me.ahoo.wow.spring.boot.query

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CallHeaders
import me.ahoo.wow.spring.boot.Host
import me.ahoo.wow.spring.boot.HostQueryFixtures.ACCOUNT
import me.ahoo.wow.spring.boot.startHost
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * `wow.query.sensitivity.display-shared` (#6509): whether the service shares its `DISPLAY` values with a peer that asks
 * on its internal port; its own revealing reads in process are not its to decide.
 */
class HostDisplaySharedTest {
    /** A list of the accounts on [host]'s internal port by the service `ledger` for itself, asking to reveal or not. */
    private fun peerRead(host: Host, reveal: Boolean): Pair<Int, String> {
        val root = Operator.of(Caller.SERVICE_SUBJECT_PREFIX + "ledger", Caller.DEFAULT_TENANT)
        val context = CallContext.of("c", Caller.DEFAULT_TENANT, Scope.NONE, Causation("c", "c"), root)
        val uri = URI.create("http://127.0.0.1:${host.internalPort}/bank/account/snapshot/query")
        val request = HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/json")
            .header(WowHeaders.SERVICE, "ledger")
            .header(WowHeaders.CALL, CallHeaders.format(context))
            .apply { if (reveal) header(WowHeaders.REVEAL, WowHeaders.REVEAL_DISPLAY) }
            .POST(HttpRequest.BodyPublishers.ofString("""{"kind":"list","filter":{"op":"match-all"},"limit":10}"""))
            .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    @Test
    fun `a service should share its DISPLAY values with a peer that asks, by default`() {
        QueryProperties().sensitivity.displayShared.assert().isTrue()
        startHost().use { host ->
            host.context.getBean(HttpQueries::class.java).displayShared.assert().isTrue()
            peerRead(host, reveal = true).first.assert().isEqualTo(200)
        }
    }

    @Test
    fun `display-shared off should refuse a peer read that asks, naming the key, and leave the service's own reads`() {
        startHost("wow.query.sensitivity.display-shared" to false).use { host ->
            host.context.getBean(HttpQueries::class.java).displayShared.assert().isFalse()
            val (status, body) = peerRead(host, reveal = true)
            status.assert().isEqualTo(403)
            body.assert().contains("wow.query.sensitivity.display-shared", "wow.auth.forbidden")
            // A peer read that does not ask is answered.
            peerRead(host, reveal = false).first.assert().isEqualTo(200)
            // The service's own revealing reads in process are not the key's to refuse.
            val api = host.context.getBean(NodeQueryApi::class.java)
            runBlocking { api.trusted().revealed().snapshots(ACCOUNT).count(CountQuery(MatchAllFilter)) }.count
                .assert().isEqualTo(0L)
        }
    }
}

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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.internal.InternalHttp
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration

/**
 * `wow.node.standby` (design 07 §3): the node starts serving reads only, refuses commands with `wow.node.standby` (503),
 * and its operator resumes it on the internal port, naming who resumes it.
 */
class HostStandbyTest {
    @Test
    fun `a host on standby should refuse commands until its operator resumes it`() {
        startHost("wow.node.standby" to "true").use { host ->
            host.node.standby.assert().isTrue()
            host.post("/bank/account/open-account", """{"owner":"alice"}""").also {
                it.status.assert().isEqualTo(503)
                it.body!!.get("code").asString().assert().isEqualTo("wow.node.standby")
            }
            // A node of the service resumes it (Wow-Service, decision D300).
            val internal = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:${host.internalPort}")
                .defaultHeader(WowHeaders.SERVICE, "bank-service")
                .responseTimeout(Duration.ofSeconds(30)).build()
            internal.get().uri(InternalHttp.STANDBY_PATH).answer().body!!.get("standby").asBoolean().assert().isTrue()
            internal.post().uri(InternalHttp.RESUME_PATH).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"by":"operator-1","reason":"cut-over"}""").answer().also {
                    it.status.assert().isEqualTo(200)
                    it.body!!.get("standby").asBoolean().assert().isFalse()
                    it.body.get("resumedBy").asString().assert().isEqualTo("operator-1")
                }
            host.node.standby.assert().isFalse()
            host.node.resumption!!.by.assert().isEqualTo("operator-1")
            host.post("/bank/account/open-account", """{"owner":"alice"}""").status.assert().isEqualTo(200)
        }
    }

    @Test
    fun `a host without the switch should start as before`() {
        startHost().use { host -> host.node.standby.assert().isFalse() }
    }
}

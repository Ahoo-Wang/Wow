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

package me.ahoo.wow.http.internal

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.runtime.cluster.ClusterChannelHandler
import me.ahoo.wow.runtime.cluster.ForwardReply
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The operator routes of a node on standby (design 07 §3): its status and its resume, from a node of its service only,
 * the resume naming who resumes it for the audit.
 */
class StandbyRoutesTest {
    private var standby = true
    private val resumes = mutableListOf<Pair<String, String?>>()
    private val control = object : StandbyControl {
        override fun status(): StandbyControl.Status = StandbyControl.Status(
            node = "node-1",
            standby = standby,
            resumedBy = resumes.lastOrNull()?.first,
            reason = resumes.lastOrNull()?.second,
            resumedAt = if (resumes.isEmpty()) null else 42L,
        )

        override suspend fun resume(by: String, reason: String?): StandbyControl.Status {
            resumes += by to reason
            standby = false
            return status()
        }
    }
    private val server = InternalServer(
        internalRoutes(
            ClusterChannelHandler { ForwardReply(result = committed(it.command.id)) },
            {},
            service = SERVICE,
            standby = control,
        ),
    )
    private val client = InternalHttp.client()

    @AfterEach
    fun close() = server.close()

    private fun send(path: String, body: String? = null, service: String? = SERVICE): HttpResponse<String> {
        val request = HttpRequest.newBuilder(InternalHttp.uri(server.address, path))
        service?.let { request.header(WowHeaders.SERVICE, it) }
        if (body == null) {
            request.GET()
        } else {
            request.header(
                "Content-Type",
                "application/json"
            ).POST(HttpRequest.BodyPublishers.ofString(body))
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `the status and the resume take a node of the service, and the resume names who resumes`() {
        send(InternalHttp.STANDBY_PATH, service = null).statusCode().assert().isEqualTo(401)
        send(InternalHttp.STANDBY_PATH, service = "ordering-service").statusCode().assert().isEqualTo(403)
        send(InternalHttp.STANDBY_PATH).also {
            it.statusCode().assert().isEqualTo(200)
            it.body().assert().contains("\"standby\":true").contains("\"resumedBy\":null")
        }
        send(InternalHttp.RESUME_PATH, """{"by":"op"}""", service = null).statusCode().assert().isEqualTo(401)
        send(InternalHttp.RESUME_PATH, """{"reason":"cut-over"}""").also {
            it.statusCode().assert().isEqualTo(400)
            it.body().assert().contains("names who resumes the node")
        }
        send(InternalHttp.RESUME_PATH, """{"by":" "}""").statusCode().assert().isEqualTo(400)
        send(InternalHttp.RESUME_PATH, """{"by":7}""").statusCode().assert().isEqualTo(400)
        send(InternalHttp.RESUME_PATH, "not json").statusCode().assert().isEqualTo(400)
        resumes.assert().isEmpty()

        send(InternalHttp.RESUME_PATH, """{"by":"operator-1","reason":"cut-over"}""").also {
            it.statusCode().assert().isEqualTo(200)
            it.body().assert().contains("\"standby\":false").contains("\"resumedBy\":\"operator-1\"")
                .contains("\"reason\":\"cut-over\"").contains("\"resumedAt\":42")
        }
        send(InternalHttp.RESUME_PATH, """{"by":"operator-2"}""").statusCode().assert().isEqualTo(200)
        // A reason that is not a string is none.
        send(InternalHttp.RESUME_PATH, """{"by":"operator-3","reason":5}""").statusCode().assert().isEqualTo(200)
        resumes.assert().containsExactly("operator-1" to "cut-over", "operator-2" to null, "operator-3" to null)
    }

    @Test
    fun `without a standby control the routes are not served`() {
        InternalServer(internalRoutes(ClusterChannelHandler { ForwardReply(result = committed(it.command.id)) }, {}))
            .use { plain ->
                val request = HttpRequest.newBuilder(
                    InternalHttp.uri(plain.address, InternalHttp.STANDBY_PATH)
                ).GET().build()
                client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode().assert().isEqualTo(404)
            }
    }

    private companion object {
        const val SERVICE = "bank-service"
    }
}

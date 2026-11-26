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

package me.ahoo.wow.spring.boot.http

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spring.boot.startHost
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.Socket

/**
 * D485 ⑪ on a host's public port, with raw requests: a command's body sent with no `Content-Type`, of a known length
 * or chunked, is 415 and the command does not run; with its type, or with no body, it runs.
 */
class MissingContentTypeTest {
    private fun send(port: Int, vararg lines: String, body: String = ""): String = Socket().use { client ->
        client.connect(InetSocketAddress("127.0.0.1", port), 1_000)
        client.soTimeout = 10_000
        val head = (listOf("POST /bank/account/open-account HTTP/1.1", "Host: 127.0.0.1", "Connection: close") + lines)
            .joinToString("\r\n", postfix = "\r\n\r\n")
        client.getOutputStream().apply {
            write((head + body).toByteArray())
            flush()
        }
        client.getInputStream().readAllBytes().toString(Charsets.UTF_8)
    }

    @Test
    fun `a body with no Content-Type should be 415, of a known length or chunked`() {
        startHost().use { host ->
            val opening = """{"owner":"alice"}"""
            listOf(
                send(host.publicPort, "Content-Length: ${opening.length}", body = opening),
                send(host.publicPort, "Transfer-Encoding: chunked", body = chunked(opening)),
            ).forEach { answer ->
                answer.assert().startsWith("HTTP/1.1 415").contains("application/problem+json")
                    .contains("A command is sent with Content-Type application/json or application/*+json; none was given.")
            }
            send(host.publicPort, "Content-Type: application/json", "Content-Length: ${opening.length}", body = opening)
                .assert().startsWith("HTTP/1.1 200")
            send(host.publicPort, "Content-Type: application/json", "Transfer-Encoding: chunked", body = chunked(opening))
                .assert().startsWith("HTTP/1.1 200")
            // With no body there is no type to give: the command's body is its empty object.
            send(host.publicPort, "Content-Length: 0").assert().startsWith("HTTP/1.1 200")
            send(host.publicPort).assert().startsWith("HTTP/1.1 200")
        }
    }

    private fun chunked(text: String): String = "${text.length.toString(16)}\r\n$text\r\n0\r\n\r\n"
}

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

package me.ahoo.wow.http

import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.netty.http.server.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** The first answer to a request whose body was announced and never sent: its status line and its body. */
class AnnouncedAnswer(val statusLine: String, val bytes: ByteArray) {
    val body: String get() = bytes.toString(Charsets.UTF_8)
}

/**
 * [routes] on a real Reactor Netty server, as a node serves them, for [block]: an in-process client writes the body it
 * is given and its true `Content-Length`, so a body announced past the limit and not sent is a real server's to show.
 */
fun <T> servingOnNetty(routes: RouterFunction<ServerResponse>, block: (port: Int) -> T): T {
    val server = HttpServer.create().host("127.0.0.1").port(0)
        .handle(ReactorHttpHandlerAdapter(RouterFunctions.toHttpHandler(routes))).bindNow()
    try {
        return block(server.port())
    } finally {
        server.disposeNow()
    }
}

/**
 * Sends `POST [path]` with [headers], announcing a JSON body of [length] bytes with `Expect: 100-continue`, sends no
 * body, and reads the first answer (#7001): a route that refuses the body from its `Content-Length` answers it in
 * place of `100 Continue`; one that reads it first answers `100 Continue`.
 */
fun announce(port: Int, path: String, headers: List<String> = listOf("X-User: alice"), length: Int = 300 * 1024):
    AnnouncedAnswer = Socket().use { socket ->
    socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
    socket.soTimeout = 5_000
    val head = listOf(
        "POST $path HTTP/1.1",
        "Host: 127.0.0.1",
        "Content-Type: application/json",
        "Content-Length: $length",
        "Expect: 100-continue",
    ) + headers
    socket.getOutputStream().apply {
        write((head.joinToString("\r\n") + "\r\n\r\n").toByteArray())
        flush()
    }
    val input = socket.getInputStream()
    val read = ByteArrayOutputStream()
    while (!read.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) {
        val next = input.read()
        check(next >= 0) { "The connection closed before an answer: $read" }
        read.write(next)
    }
    val lines = read.toString(Charsets.ISO_8859_1).trimEnd().split("\r\n")
    val contentLength = lines.drop(1).firstOrNull { it.lowercase().startsWith("content-length:") }
        ?.substringAfter(':')?.trim()?.toInt() ?: 0
    val body = ByteArray(contentLength)
    var at = 0
    while (at < contentLength) {
        val n = input.read(body, at, contentLength - at)
        check(n >= 0) { "The connection closed within the answer's body." }
        at += n
    }
    AnnouncedAnswer(lines.first(), body)
}

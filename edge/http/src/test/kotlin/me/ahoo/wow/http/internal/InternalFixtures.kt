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

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.runtime.cluster.ForwardCommand
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.netty.DisposableServer
import reactor.netty.http.HttpProtocol
import reactor.netty.http.server.HttpServer
import tools.jackson.databind.node.JsonNodeFactory
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

const val TEST_CONTEXT = "bank"

/** How many times a test binds a server at a port it released, another process taking it in between. */
const val BIND_ATTEMPTS = 5

/**
 * An internal port as the host serves it (decision D109): bare Reactor Netty taking HTTP/1.1 and h2c, serving
 * [routes] and upgrading a connection's first request of up to [upgradeBody] bytes. [versions] records the protocol
 * each request arrived over.
 */
class InternalServer(
    routes: RouterFunction<ServerResponse>,
    upgradeBody: Int = InternalHttp.MAX_UPGRADE_BODY,
    port: Int = 0,
) : AutoCloseable {
    val versions = CopyOnWriteArrayList<String>()
    private val adapter = ReactorHttpHandlerAdapter(RouterFunctions.toHttpHandler(routes))
    private val server: DisposableServer = HttpServer.create()
        .host("127.0.0.1")
        .port(port)
        .protocol(HttpProtocol.HTTP11, HttpProtocol.H2C)
        .httpRequestDecoder { it.h2cMaxContentLength(upgradeBody) }
        .handle { request, response ->
            versions += request.version().text()
            adapter.apply(request, response)
        }
        .bindNow()

    val address: String get() = "127.0.0.1:${server.port()}"

    override fun close() {
        server.disposeNow()
    }
}

/**
 * Runs [block] with a loopback port no server listens on: a socket is bound to it without listening, so a connection
 * to it is refused, and no other process can take the port while [block] runs.
 */
inline fun <T> withRefusingPort(block: (port: Int) -> T): T = Socket().use { socket ->
    socket.bind(InetSocketAddress("127.0.0.1", 0))
    block(socket.localPort)
}

fun ref(id: String, context: String = TEST_CONTEXT): AggregateRef = AggregateType(context, "account").ref("t", id)

fun forwardCommand(
    id: String = "c-1",
    context: String = TEST_CONTEXT,
    deadline: Long = System.currentTimeMillis() + 10_000,
): ForwardCommand = ForwardCommand(
    command = CommandWire(
        type = "deposit",
        id = id,
        requestId = "r-$id",
        ref = ref("acc-1", context),
        body = JsonNodeFactory.instance.objectNode().put("amount", 1),
    ),
    deadline = deadline,
)

fun committed(commandId: String, context: String = TEST_CONTEXT): CommandResult = CommandResult(
    commandId = commandId,
    requestId = "r-$commandId",
    correlationId = commandId,
    ref = ref("acc-1", context),
    hop = Hop.committed(context),
    function = FunctionInfo(FunctionKind.COMMAND, context, "account", "deposit"),
    status = CommandResult.Status.SUCCEEDED,
    version = 1,
    results = mapOf("balance" to 1),
    signalTime = 1L,
)

/** The [T] that [block] throws, failing when it throws none. */
suspend inline fun <reified T : Throwable> failsWith(block: suspend () -> Unit): T {
    try {
        block()
    } catch (thrown: Throwable) {
        if (thrown is T) {
            return thrown
        }
        throw thrown
    }
    throw AssertionError("Expected a ${T::class.simpleName}.")
}

/** Waits until [condition] holds, failing after [timeout]. */
suspend fun eventually(timeout: Duration = 10.seconds, condition: suspend () -> Boolean) {
    withTimeout(timeout) {
        while (!condition()) {
            delay(5)
        }
    }
}

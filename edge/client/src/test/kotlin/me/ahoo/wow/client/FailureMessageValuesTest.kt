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

package me.ahoo.wow.client

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.kernel.FailureReasons
import me.ahoo.wow.spi.call.OutgoingCall
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.Authenticator
import java.net.ConnectException
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.time.Duration.Companion.seconds

/**
 * A problem's message is a caller's answer, which a parked reason keeps as it is ([FailureReasons]): the failure a
 * call or a request ran into is named there by its class, never by its message, which may carry the values it failed
 * on (#6796); the failure itself goes with the problem as its cause, to the node's log.
 */
class FailureMessageValuesTest {
    private val box = AggregateType("mail", "box")

    private val call = OutgoingCall(
        "mail.box.DeliverItems",
        Command(
            "c-1",
            "r-1",
            box.ref("t", "b-1"),
            mapOf("items" to listOf("mug")),
            scope = Scope("alice"),
            causation = Causation("root", "basket/t/alice@2"),
        ),
    )

    /** A client whose every request fails with [failure], as the JDK's does when the exchange breaks. */
    private fun failingWith(failure: Throwable): HttpClient = object : HttpClient() {
        private val real = newHttpClient()

        override fun cookieHandler(): Optional<CookieHandler> = real.cookieHandler()

        override fun connectTimeout(): Optional<java.time.Duration> = real.connectTimeout()

        override fun followRedirects(): Redirect = real.followRedirects()

        override fun proxy(): Optional<ProxySelector> = real.proxy()

        override fun sslContext(): SSLContext = real.sslContext()

        override fun sslParameters(): SSLParameters = real.sslParameters()

        override fun authenticator(): Optional<Authenticator> = real.authenticator()

        override fun version(): Version = real.version()

        override fun executor(): Optional<Executor> = real.executor()

        override fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> =
            throw failure

        override fun <T> sendAsync(
            request: HttpRequest,
            handler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(failure)

        override fun <T> sendAsync(
            request: HttpRequest,
            handler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
        ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(failure)
    }

    private fun callFailing(failure: Throwable): WowProblemException {
        val caller = HttpCommandCaller(
            mapOf("mail" to URI.create("http://127.0.0.1:9")),
            "basket-service",
            failingWith(failure),
            5.seconds,
            contracts = contractOf(box, HttpCommandClientTest.DeliverItems::class.java).map { it.manifest },
        )
        return checkNotNull(runCatching { runBlocking { caller.call(call) } }.exceptionOrNull() as? WowProblemException)
    }

    private fun WowProblemException.assertNamesNoValue(failure: Throwable) {
        message.assert().doesNotContain(SECRET).contains(failure.javaClass.name)
        FailureReasons.DEFAULT.reasonOf(this).assert().doesNotContain(SECRET)
        cause.assert().isSameAs(failure)
    }

    @Test
    fun `a call that fails names its failure's class, never its message`() {
        val failure = IllegalStateException("$SECRET in the request")
        callFailing(failure).assertNamesNoValue(failure)
    }

    @Test
    fun `a call no node takes names its failure's class, never its message`() {
        val failure = ConnectException("$SECRET refused")
        callFailing(failure).assertNamesNoValue(failure)
    }

    @Test
    fun `a call not answered names its failure's class, never its message`() {
        val failure = IOException("$SECRET reset")
        callFailing(failure).assertNamesNoValue(failure)
    }

    @Test
    fun `a request not answered names its failure's class, never its message, whatever its kind`() {
        val request = PeerRequest("mail", "/mail/box", Caller.of(tenantId = "t"), null)
        val failure = IOException("$SECRET reset")
        Exchange.entries.forEach { kind ->
            kind.unanswered(request, failure).detail.assert().describedAs("$kind").doesNotContain(SECRET)
        }
    }

    private companion object {
        const val SECRET = "password=hunter2"
    }
}

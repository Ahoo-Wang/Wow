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
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.runtime.contract.ContractDirectory
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The settings of the clients: endpoints, credentials, modes, retries and the names of the peers' commands. */
class ClientSettingsTest {
    @Test
    fun `fixed endpoints should be http or https, by port`(): Unit = runBlocking {
        val public = URI.create("http://mail:8080")
        val internal = URI.create("https://mail:9090")
        val endpoints = EndpointResolver.of(mapOf("mail" to public), mapOf("mail" to internal))
        endpoints.resolve("mail", Port.PUBLIC).assert().isEqualTo(public)
        endpoints.resolve("mail", Port.INTERNAL).assert().isEqualTo(internal)
        endpoints.resolve("box", Port.PUBLIC).assert().isNull()
        EndpointResolver.NONE.resolve("mail", Port.PUBLIC).assert().isNull()
        assertThrownBy<IllegalArgumentException> { EndpointResolver.of(mapOf("mail" to URI.create("ftp://mail"))) }
    }

    @Test
    fun `credentials should present a bearer token, or forward the request's headers`(): Unit = runBlocking {
        ServiceCredential.bearer("token").authorization().assert().isEqualTo("Bearer token")
        ServiceCredential.NONE.authorization().assert().isNull()
        assertThrownBy<IllegalArgumentException> { ServiceCredential.bearer(" ") }
        UserCredential.NONE.headers(Caller.ANONYMOUS).assert().isEmpty()
        UserCredential.FORWARDED.headers(Caller.ANONYMOUS).assert().isEmpty()
        val forwarded = ForwardedHeaders(mapOf("Authorization" to "Bearer u"))
        withContext(forwarded) { UserCredential.FORWARDED.headers(Caller.ANONYMOUS) }
            .assert().isEqualTo(mapOf("Authorization" to "Bearer u"))
        forwarded.toString().assert().isEqualTo("ForwardedHeaders([Authorization])")
        ClientMode.Service(ServiceCredential.NONE).let {
            it.port.assert().isEqualTo(Port.INTERNAL)
            it.toString().assert().isEqualTo("ClientMode.Service")
        }
        ClientMode.User(UserCredential.NONE).let {
            it.port.assert().isEqualTo(Port.PUBLIC)
            it.toString().assert().isEqualTo("ClientMode.User")
        }
    }

    @Test
    fun `a retry policy should wait Retry-After within its bound, else its doubling backoff`() {
        val policy = RetryPolicy.of(maxAttempts = 4, maxRetryAfter = 2.seconds, backoff = 100.milliseconds)
        policy.delayBefore(1, null).assert().isEqualTo(100.milliseconds)
        policy.delayBefore(3, null).assert().isEqualTo(400.milliseconds)
        policy.delayBefore(1, 2.seconds).assert().isEqualTo(2.seconds)
        policy.delayBefore(1, 3.seconds).assert().isNull()
        policy.delayBefore(4, null).assert().isNull()
        RetryPolicy.of(
            maxRetryAfter = 1.seconds,
            backoff = 800.milliseconds
        ).delayBefore(2, null).assert().isEqualTo(1.seconds)
        RetryPolicy.NONE.delayBefore(1, Duration.ZERO).assert().isNull()
        RetryPolicy.of().assert().isEqualTo(RetryPolicy.DEFAULT)
        RetryPolicy.of().hashCode().assert().isEqualTo(RetryPolicy.DEFAULT.hashCode())
        (RetryPolicy.DEFAULT == RetryPolicy.NONE).assert().isFalse()
        RetryPolicy.DEFAULT.toString().assert().startsWith("RetryPolicy(maxAttempts=3")
        assertThrownBy<IllegalArgumentException> { RetryPolicy.of(maxAttempts = 0) }
        assertThrownBy<IllegalArgumentException> { RetryPolicy.of(maxRetryAfter = (-1).seconds) }
        assertThrownBy<IllegalArgumentException> { RetryPolicy.of(backoff = Duration.ZERO) }
    }

    @Test
    fun `only a connection never made should count as a request not sent`() {
        with(HttpTransport) {
            java.net.ConnectException("refused").unsent().assert().isTrue()
            java.net.http.HttpConnectTimeoutException("late").unsent().assert().isTrue()
            java.net.http.HttpTimeoutException("no answer").unsent().assert().isFalse()
            java.io.IOException("reset").unsent().assert().isFalse()
        }
    }

    @Test
    fun `a builder should check its bounds`() {
        assertThrownBy<IllegalArgumentException> { WowClient.builder().timeout(Duration.ZERO) }
        assertThrownBy<IllegalArgumentException> { WowClient.builder().connectTimeout(Duration.ZERO) }
        WowClient.builder().timeout(1.seconds).connectTimeout(1.seconds).httpClient(HttpCommandCaller.client())
            .classLoader(javaClass.classLoader).user(UserCredential.NONE).build().commands.assert().isNotNull()
    }

    @Test
    fun `the peers' commands should be named by their contracts`() {
        val mail = ContextManifest(
            "mail",
            aggregates = listOf(
                AggregateManifest(
                    "box",
                    state = StateManifest(Manifest.schemaRef("mail.BoxState")),
                    commands = listOf(CommandManifest("DeliverItems", schema = Manifest.schemaRef(DELIVER))),
                ),
            ),
        )
        val contracts =
            ContractDirectory(Manifest(), listOf(ManifestFragment("mail", Manifest(contexts = listOf(mail)))))
        val names = ContractCommandTypes(contracts)
        val box = AggregateType("mail", "box")
        names.nameOf(box, HttpCommandClientTest.DeliverItems::class.java).assert().isEqualTo("mail.box.DeliverItems")
        names.nameOf(AggregateType("mail", "other"), HttpCommandClientTest.DeliverItems::class.java).assert().isNull()
        names.aggregatesOf(HttpCommandClientTest.DeliverItems::class.java).assert().containsExactly(box)
    }

    private companion object {
        val DELIVER: String = HttpCommandClientTest.DeliverItems::class.java.name
    }
}

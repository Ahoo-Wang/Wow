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

package me.ahoo.wow.example.eshop.e2e.cluster

import me.ahoo.wow.example.eshop.e2e.Answer
import me.ahoo.wow.example.eshop.e2e.json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Who sends a request: a CoSec principal ([id], [roles]) whose token the gateway verifies and forwards. Behind it, each
 * eShop service reads the caller from the token in CoSec's inject mode: a principal with the role `employee` is an
 * employee (`wow.cosec.tenant-level.roles`), any other a buyer; ops reads its operators' roles the same way.
 */
data class Who(val id: String, val roles: List<String> = emptyList()) {
    companion object {
        fun buyer(id: String): Who = Who(id)
        val EMPLOYEE: Who = Who("erin-employee", roles = listOf("employee"))
        val OPERATOR: Who = Who("olivia-operator", roles = listOf("ops.operator"))
        val VIEWER: Who = Who("victor-viewer", roles = listOf("ops.viewer"))
    }
}

/** The CoSec access tokens of the run's principals: HMAC256 with the gateway's secret, as CoSec's JWT module reads them. */
class Tokens(private val secret: String) {
    fun of(who: Who, ttl: Duration = Duration.ofHours(2)): String {
        val now = System.currentTimeMillis() / MILLIS
        val header = encode("""{"alg":"HS256","typ":"JWT"}""")
        val claims = linkedMapOf<String, Any>(
            "jti" to UUID.randomUUID().toString(),
            "sub" to who.id,
            "iat" to now,
            "exp" to now + ttl.seconds,
        )
        if (who.roles.isNotEmpty()) claims["roles"] = who.roles
        val payload = encode(json.writeValueAsString(claims))
        val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(secret.toByteArray(), ALGORITHM)) }
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal("$header.$payload".toByteArray())
        )
        return "$header.$payload.$signature"
    }

    private fun encode(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        text.toByteArray()
    )

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val MILLIS = 1000
    }
}

/**
 * The shop as a client reaches it: through the CoSec gateway's node port (`ga.gateway`), each request with the
 * sender's token. A request that could not be sent (refused, reset, timed out) is answered with
 * [UNREACHABLE] and the reason as its body, so a fault's window counts it as a failure instead of failing the run.
 */
class Shop(private val gateway: String, private val tokens: Tokens) {
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun get(path: String, who: Who?, headers: Map<String, String> = emptyMap(), timeout: Duration = TIMEOUT): Answer =
        send(HttpRequest.newBuilder(URI.create("$gateway$path")).GET(), who, headers, timeout)

    fun post(
        path: String,
        body: String,
        who: Who?,
        headers: Map<String, String> = emptyMap(),
        timeout: Duration = TIMEOUT,
    ): Answer = send(
        HttpRequest.newBuilder(URI.create("$gateway$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)),
        who,
        headers,
        timeout,
    )

    private fun send(request: HttpRequest.Builder, who: Who?, headers: Map<String, String>, timeout: Duration): Answer {
        who?.let { request.header("Authorization", "Bearer ${tokens.of(it)}") }
        headers.forEach { (name, value) -> request.header(name, value) }
        return try {
            val response = http.send(request.timeout(timeout).build(), HttpResponse.BodyHandlers.ofString())
            Answer(response.statusCode(), response.body())
        } catch (timedOut: HttpTimeoutException) {
            Answer(UNREACHABLE, "timed out: ${timedOut.message}")
        } catch (failed: IOException) {
            Answer(UNREACHABLE, "${failed.javaClass.simpleName}: ${failed.message}")
        }
    }

    companion object {
        /** The status of a request that got no response. */
        const val UNREACHABLE = 0
        val TIMEOUT: Duration = Duration.ofSeconds(40)
    }
}

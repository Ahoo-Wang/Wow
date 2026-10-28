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

package me.ahoo.wow.http.ops

import kotlinx.coroutines.future.await
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.http.HttpJson
import me.ahoo.wow.runtime.node.ParkedView
import me.ahoo.wow.runtime.subscription.ParkedNotice
import me.ahoo.wow.runtime.subscription.ParkedNotifier
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Posts each [ParkedNotice] of [kinds] to the webhook at [uri], as JSON, through the JDK's HTTP
 * [client] within [timeout]: a response other than 2xx fails the notification, which the runtime logs.
 *
 * - [Format.GENERIC]: `{"event", "service", "group", "at", "entry", "text"}`, `entry` as the operator's routes write it
 *   ([parkedRoutes]) and `text` a Markdown summary.
 * - [Format.WECOM]: a WeCom (企业微信) group robot's Markdown message, `{"msgtype": "markdown", "markdown": {"content"}}`,
 *   as 9.x's compensation webhook sent it; a robot that answers an `errcode` other than 0 fails the notification.
 *
 * [service] names the service in the message; [headers] are added to each request (a token, say).
 */
@ExperimentalWowApi
class WebhookNotifier(
    private val uri: URI,
    private val format: Format = Format.GENERIC,
    private val kinds: Set<ParkedNotice.Kind> = ParkedNotice.Kind.entries.toSet(),
    private val service: String? = null,
    private val headers: Map<String, String> = emptyMap(),
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(timeout.toJavaDuration()).build(),
) : ParkedNotifier {
    init {
        require(uri.scheme == "http" || uri.scheme == "https") { "A webhook is an http or https URL: $uri." }
        require(timeout.isPositive()) { "A webhook's timeout is positive." }
    }

    override suspend fun notify(notice: ParkedNotice) {
        if (notice.kind !in kinds) {
            return
        }
        val request = HttpRequest.newBuilder(uri)
            .timeout(timeout.toJavaDuration())
            .header("Content-Type", "application/json")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .POST(HttpRequest.BodyPublishers.ofByteArray(HttpJson.write(bodyOf(notice))))
            .build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        check(response.statusCode() in SUCCESS) {
            "The webhook answered ${response.statusCode()}: ${response.body().take(MAX_ANSWER)}"
        }
        if (format == Format.WECOM) {
            val code = runCatching { HttpJson.mapper.readTree(response.body())[ERRCODE]?.asInt() }.getOrNull()
            check(
                code == null || code == 0
            ) { "The WeCom robot refused the message: ${response.body().take(MAX_ANSWER)}" }
        }
    }

    /** What is posted for [notice]. */
    fun bodyOf(notice: ParkedNotice): Map<String, Any?> = when (format) {
        Format.GENERIC -> linkedMapOf(
            "event" to WireNames.of(notice.kind),
            "service" to service,
            "group" to notice.group,
            "at" to Instant.ofEpochMilli(notice.at).toString(),
            "entry" to viewOf(ParkedView(notice.entry, notice.spec)),
            "text" to textOf(notice),
        )

        Format.WECOM -> mapOf("msgtype" to "markdown", "markdown" to mapOf("content" to textOf(notice)))
    }

    /** A Markdown summary of [notice]. */
    fun textOf(notice: ParkedNotice): String {
        val entry = notice.entry
        val title = when (notice.kind) {
            ParkedNotice.Kind.PARKED -> "parked"
            ParkedNotice.Kind.EXHAUSTED -> "spent its retries"
        }
        val next = notice.nextRetryAt?.let { "next retry at ${Instant.ofEpochMilli(it)}" } ?: "no automatic retry left"
        return buildString {
            append("**${entry.subscription} $title: ${entry.ref}**\n")
            service?.let { append("> service: $it\n") }
            append("> group: ${notice.group}\n")
            append("> from version: ${entry.fromVersion}")
            entry.fromPosition?.let { append(", position $it") }
            append('\n')
            append("> reason: ${entry.reason.take(MAX_REASON)}\n")
            append("> retries: ${entry.retries} of ${notice.spec.maxRetries}, $next\n")
            append("> at: ${Instant.ofEpochMilli(notice.at)}")
        }
    }

    override fun toString(): String = "WebhookNotifier($format, $uri)"

    /** The shape of the message posted. */
    enum class Format {
        /** Wow's own JSON. */
        GENERIC,

        /** A WeCom group robot's Markdown message. */
        WECOM,
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = 5.seconds
        private val SUCCESS = 200..299
        private const val ERRCODE = "errcode"
        private const val MAX_ANSWER = 200
        private const val MAX_REASON = 1_000
    }
}

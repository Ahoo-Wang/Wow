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

package me.ahoo.wow.tck.container

import org.testcontainers.elasticsearch.ElasticsearchContainer
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

object ElasticsearchLauncher {
    private const val ELASTIC_USER = "elastic"
    const val ELASTIC_PWD = "wow"
    private val BASIC_AUTH = Base64.getEncoder().encodeToString(
        "$ELASTIC_USER:$ELASTIC_PWD".toByteArray(StandardCharsets.UTF_8),
    )
    val ELASTICSEARCH_CONTAINER: ElasticsearchContainer = ElasticsearchContainer(
        DockerImageName
            .parse("docker.elastic.co/elasticsearch/elasticsearch")
            .withTag("9.2.6"),
    )
        .withPassword(ELASTIC_PWD)
        .withNetworkAliases("elasticsearch")
        .withReuse(false)
        .withStartupTimeout(Duration.ofMinutes(5))

    init {
        ELASTICSEARCH_CONTAINER.start()
        waitUntilAuthenticated()
    }

    val isRunning = ELASTICSEARCH_CONTAINER.isRunning

    private fun waitUntilAuthenticated() {
        val httpClient = HttpClient.newBuilder()
            .sslContext(ELASTICSEARCH_CONTAINER.createSslContextFromCa())
            .connectTimeout(Duration.ofSeconds(5))
            .build()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://${ELASTICSEARCH_CONTAINER.httpHostAddress}/_security/_authenticate"))
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Basic $BASIC_AUTH")
            .GET()
            .build()

        ElasticsearchAuthenticationWaiter.waitUntilAuthenticated(
            authenticate = {
                httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            },
        )
    }
}

internal object ElasticsearchAuthenticationWaiter {
    private val SUCCESSFUL_STATUS_CODES = 200..299
    private val DEFAULT_TIMEOUT = Duration.ofMinutes(2)
    private val DEFAULT_RETRY_INTERVAL = Duration.ofSeconds(1)
    private const val AUTHENTICATION_TIMEOUT_MESSAGE =
        "Elasticsearch container did not accept authenticated requests before timeout."
    private const val AUTHENTICATION_INTERRUPTED_MESSAGE =
        "Interrupted while waiting for Elasticsearch authenticated requests."

    fun waitUntilAuthenticated(
        timeout: Duration = DEFAULT_TIMEOUT,
        retryInterval: Duration = DEFAULT_RETRY_INTERVAL,
        authenticate: () -> Int,
        sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
        nanoTime: () -> Long = System::nanoTime,
    ) {
        requirePositive(timeout, "timeout")
        requirePositive(retryInterval, "retryInterval")
        val deadline = nanoTime() + timeout.toNanos()
        var lastError: Throwable? = null
        while (nanoTime() < deadline) {
            try {
                val statusCode = authenticate()
                if (statusCode in SUCCESSFUL_STATUS_CODES) {
                    return
                }
                lastError = IllegalStateException("Unexpected Elasticsearch authentication status: $statusCode")
            } catch (error: InterruptedException) {
                abortInterrupted(error)
            } catch (error: Exception) {
                lastError = error
            }
            val sleepInterval = nextSleepInterval(deadline, retryInterval, nanoTime) ?: break
            sleepOrAbort(sleepInterval, sleep)
        }
        throw IllegalStateException(AUTHENTICATION_TIMEOUT_MESSAGE, lastError)
    }

    private fun requirePositive(duration: Duration, name: String) {
        require(!duration.isZero && !duration.isNegative) {
            "$name must be positive."
        }
    }

    private fun nextSleepInterval(
        deadline: Long,
        retryInterval: Duration,
        nanoTime: () -> Long,
    ): Duration? {
        val remainingNanos = deadline - nanoTime()
        if (remainingNanos <= 0) {
            return null
        }
        val remaining = Duration.ofNanos(remainingNanos)
        return if (remaining < retryInterval) {
            remaining
        } else {
            retryInterval
        }
    }

    private fun sleepOrAbort(retryInterval: Duration, sleep: (Duration) -> Unit) {
        try {
            sleep(retryInterval)
        } catch (error: InterruptedException) {
            abortInterrupted(error)
        }
    }

    private fun abortInterrupted(error: InterruptedException): Nothing {
        Thread.currentThread().interrupt()
        throw IllegalStateException(AUTHENTICATION_INTERRUPTED_MESSAGE, error)
    }
}

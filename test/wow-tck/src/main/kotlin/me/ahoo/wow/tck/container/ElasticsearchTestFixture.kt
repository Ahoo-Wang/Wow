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

import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher
import org.testcontainers.elasticsearch.ElasticsearchContainer
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLContext

class ElasticsearchTestFixture(
    private val indexPrefix: String = "wow_it",
) : BeforeEachCallback, AfterTestExecutionCallback, AfterEachCallback, TestWatcher {
    companion object {
        private const val REQUEST_TIMEOUT_SECONDS = 5L
        private const val DIAGNOSTIC_BODY_LIMIT = 16 * 1024
        private const val TEST_INDEX_PATTERNS = "wow.*.es,wow.*.snapshot"
        private const val HTTP_NOT_FOUND = 404
        private val TEST_LOCK = ReentrantLock()
        private val HTTP_SUCCESS = 200..299
        private val DIAGNOSTIC_PATHS = listOf(
            "cluster health" to
                "/_cluster/health?filter_path=status,timed_out,number_of_nodes,unassigned_shards," +
                "number_of_pending_tasks,task_max_waiting_in_queue_millis",
            "node pressure" to
                "/_nodes/stats/thread_pool,jvm,process?filter_path=nodes.*.name,nodes.*.thread_pool.write.*," +
                "nodes.*.thread_pool.search.*,nodes.*.jvm.mem.heap_used_percent," +
                "nodes.*.process.open_file_descriptors,nodes.*.process.max_file_descriptors",
        )
    }

    private val managedResources = linkedMapOf<String, AutoCloseable>()
    private var elasticsearchContainer: ElasticsearchContainer? = null
    private var lockAcquired: Boolean = false
    private var diagnosticsPrinted: Boolean = false
    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .sslContext(sslContext)
            .connectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .build()
    }

    val username: String
        get() = WowTestContainers.ELASTIC_USER

    val password: String
        get() = WowTestContainers.elasticPassword

    val hostAddress: String
        get() = elasticsearch().httpHostAddress

    val sslContext: SSLContext
        get() = elasticsearch().createSslContextFromCa()

    override fun beforeEach(context: ExtensionContext) {
        TEST_LOCK.lock()
        lockAcquired = true
        diagnosticsPrinted = false
        try {
            waitUntilAuthenticated()
            deleteTestIndices()
        } catch (error: Throwable) {
            releaseLock()
            throw error
        }
    }

    override fun afterTestExecution(context: ExtensionContext) {
        context.executionException.ifPresent { cause ->
            printFailureDiagnostics(cause)
        }
    }

    fun index(name: String): String = "${ContainerTestIds.nextName(indexPrefix)}_$name"

    @Suppress("UNCHECKED_CAST")
    fun <T : AutoCloseable> getOrCreateResource(key: String, factory: () -> T): T {
        return synchronized(managedResources) {
            managedResources.getOrPut(key, factory) as T
        }
    }

    override fun afterEach(context: ExtensionContext) {
        var cleanupFailure: Throwable? = null
        try {
            deleteTestIndices()
        } catch (error: Throwable) {
            cleanupFailure = error
        }
        try {
            closeManagedResources()
        } catch (error: Throwable) {
            cleanupFailure = cleanupFailure.combine(error)
        } finally {
            releaseLock()
        }
        cleanupFailure?.let { throw it }
    }

    internal fun closeManagedResources() {
        val resources = synchronized(managedResources) {
            managedResources.values.toList().asReversed().also {
                managedResources.clear()
            }
        }
        var closeFailure: Throwable? = null
        resources.forEach { resource ->
            try {
                resource.close()
            } catch (error: Throwable) {
                if (closeFailure == null) {
                    closeFailure = error
                } else {
                    closeFailure.addSuppressed(error)
                }
            }
        }
        closeFailure?.let { throw it }
    }

    private fun elasticsearch(): ElasticsearchContainer {
        return elasticsearchContainer ?: WowTestContainers.elasticsearch
            .also {
                elasticsearchContainer = it
            }
    }

    fun waitUntilAuthenticated() {
        ElasticsearchAuthenticationWaiter.waitUntilAuthenticated(
            authenticate = {
                sendRequest("/_security/_authenticate").statusCode()
            },
        )
    }

    override fun testFailed(context: ExtensionContext, cause: Throwable?) {
        printFailureDiagnostics(cause)
    }

    private fun printFailureDiagnostics(cause: Throwable?) {
        if (diagnosticsPrinted) {
            return
        }
        diagnosticsPrinted = true
        ContainerDiagnostics.printFailure("elasticsearch", elasticsearchContainer, cause)
        printElasticsearchDiagnostics()
    }

    private fun deleteTestIndices() {
        if (elasticsearchContainer == null) {
            return
        }
        val resolveResponse = sendRequest("/_resolve/index/$TEST_INDEX_PATTERNS?expand_wildcards=all")
        if (resolveResponse.statusCode() == HTTP_NOT_FOUND) {
            return
        }
        check(resolveResponse.statusCode() in HTTP_SUCCESS) {
            "Resolve Elasticsearch test indices failed: HTTP ${resolveResponse.statusCode()}, " +
                "body=${resolveResponse.body().take(DIAGNOSTIC_BODY_LIMIT)}"
        }
        val indices = JsonSerializer.readValue(resolveResponse.body(), JsonNode::class.java)
            .path("indices")
            .mapNotNull { it.path("name").asString().takeIf(String::isNotBlank) }
        if (indices.isEmpty()) {
            return
        }
        val deleteResponse = sendRequest("/${indices.joinToString(",")}", "DELETE")
        check(deleteResponse.statusCode() in HTTP_SUCCESS || deleteResponse.statusCode() == HTTP_NOT_FOUND) {
            "Delete Elasticsearch test indices failed: HTTP ${deleteResponse.statusCode()}, " +
                "body=${deleteResponse.body().take(DIAGNOSTIC_BODY_LIMIT)}"
        }
    }

    private fun printElasticsearchDiagnostics() {
        val clientCount = synchronized(managedResources) { managedResources.size }
        val clientThreadCount = Thread.getAllStackTraces().keys.count {
            it.name.startsWith("elasticsearch-rest-client-")
        }
        System.err.println(
            "Elasticsearch fixture resources: managedClients=$clientCount, clientThreads=$clientThreadCount",
        )
        DIAGNOSTIC_PATHS.forEach { (name, path) ->
            val diagnostic = runCatching {
                val response = sendRequest(path)
                "status=${response.statusCode()}, body=${response.body().take(DIAGNOSTIC_BODY_LIMIT)}"
            }.getOrElse {
                "unavailable: ${it::class.qualifiedName}: ${it.message}"
            }
            System.err.println("Elasticsearch $name: $diagnostic")
        }
    }

    private fun sendRequest(path: String, method: String = "GET"): HttpResponse<String> {
        val basicAuth = Base64.getEncoder().encodeToString(
            "$username:$password".toByteArray(StandardCharsets.UTF_8),
        )
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("https://$hostAddress$path"))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Authorization", "Basic $basicAuth")
        val request = when (method) {
            "DELETE" -> requestBuilder.DELETE().build()
            else -> requestBuilder.GET().build()
        }
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun releaseLock() {
        if (lockAcquired) {
            lockAcquired = false
            TEST_LOCK.unlock()
        }
    }

    private fun Throwable?.combine(error: Throwable): Throwable {
        return this?.also { it.addSuppressed(error) } ?: error
    }
}

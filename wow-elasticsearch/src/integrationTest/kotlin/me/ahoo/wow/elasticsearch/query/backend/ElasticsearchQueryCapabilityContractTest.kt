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

package me.ahoo.wow.elasticsearch.query.backend

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.SearchRequest
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.elasticsearch.ElasticsearchSearchResponseGate
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.query.backend.PortableQueryDataset
import me.ahoo.wow.tck.query.backend.QueryCapabilityContract
import me.ahoo.wow.tck.query.backend.QueryCapabilityFixture
import me.ahoo.wow.tck.query.backend.QueryNativeCapabilityCase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.test.StepVerifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ElasticsearchQueryCapabilityContractTest {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture("es_query_capability")

    private lateinit var client: ReactiveElasticsearchClient
    private lateinit var portableFixture: ElasticsearchPortableQueryBackendFixture
    private lateinit var requestMonitor: ElasticsearchCapabilityRequestMonitor

    @BeforeEach
    fun prepareCapabilityIndex() {
        val gate = ElasticsearchSearchResponseGate()
        requestMonitor = ElasticsearchCapabilityRequestMonitor()
        client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(
            elasticsearch,
            gate,
            requestMonitor::record,
        )
        portableFixture = ElasticsearchPortableQueryBackendFixture(client, QueryDocumentKind.SNAPSHOT, gate)
        StepVerifier.create(portableFixture.prepare(PortableQueryDataset)).verifyComplete()
        portableFixture.backendFactory.reset()
        requestMonitor.reset()
    }

    @AfterEach
    fun clearCapabilityIndex() {
        StepVerifier.create(portableFixture.clear()).verifyComplete()
    }

    @TestFactory
    fun elasticsearchFullTextObeysSharedCapabilityContract() = QueryCapabilityContract(
        ElasticsearchFullTextCapabilityFixture(client, portableFixture.backendFactory, requestMonitor),
    ).dynamicTests()

    @TestFactory
    fun elasticsearchNativeObeysSharedCapabilityContract() = QueryCapabilityContract(
        ElasticsearchNativeCapabilityFixture(client, portableFixture.backendFactory, requestMonitor),
    ).dynamicTests()

    @Test
    fun rawProbeCountsUnexpectedSearchRequest() {
        val request = SearchRequest.of { search ->
            search.index(
                PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT).namedAggregate.toSnapshotIndexName(),
            ).size(0)
        }
        StepVerifier.create(client.search(request, Map::class.java))
            .expectNextCount(1)
            .verifyComplete()

        val fixture = ElasticsearchFullTextCapabilityFixture(client, portableFixture.backendFactory, requestMonitor)
        assertEquals(mapOf("search" to 1L), fixture.rawCommands)
    }
}

private abstract class ElasticsearchCapabilityFixture(
    protected val client: ReactiveElasticsearchClient,
    private val observer: ElasticsearchObservableQueryBackendFactory,
    private val requestMonitor: ElasticsearchCapabilityRequestMonitor,
) : QueryCapabilityFixture {
    final override val target = PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT)
    open override val schema = PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT)
    final override val rawCommands: Map<String, Long>
        get() = requestMonitor.snapshot()
    final override val successfulRawCommands: Map<String, Long> = mapOf(
        "exists" to 1L,
        "mapping" to 1L,
        "settings" to 1L,
        "count" to 1L,
    )

    final override fun reset() {
        observer.reset()
        requestMonitor.reset()
    }
}

private class ElasticsearchFullTextCapabilityFixture(
    client: ReactiveElasticsearchClient,
    observer: ElasticsearchObservableQueryBackendFactory,
    requestMonitor: ElasticsearchCapabilityRequestMonitor,
) : ElasticsearchCapabilityFixture(client, observer, requestMonitor) {
    override val id: String = "elasticsearch-full-text"
    override val capabilityId = QueryCapabilityId(ElasticsearchQueryBackendFactory.FULL_TEXT_CAPABILITY)
    override val expression = FullTextExpression(capabilityId, "你好", setOf(PortableQueryDataset.TITLE))
    override val backendFactory = ElasticsearchQueryBackendFactory(client)
}

private class ElasticsearchNativeCapabilityFixture(
    client: ReactiveElasticsearchClient,
    observer: ElasticsearchObservableQueryBackendFactory,
    requestMonitor: ElasticsearchCapabilityRequestMonitor,
) : ElasticsearchCapabilityFixture(client, observer, requestMonitor) {
    override val id: String = "elasticsearch-native"
    override val capabilityId = QueryCapabilityId(ElasticsearchQueryBackendFactory.NATIVE_CAPABILITY)
    override val schema = super.schema.withField(
        super.schema.fields.getValue(PortableQueryDataset.TITLE).copy(
            capabilities = super.schema.fields.getValue(PortableQueryDataset.TITLE).capabilities + capabilityId,
        ),
    )
    override val expression = native("elasticsearch", "capability-title")
    override val backendFactory = ElasticsearchQueryBackendFactory(client, templates())
    override val nativePreflightCases: List<QueryNativeCapabilityCase> = listOf(
        QueryNativeCapabilityCase("wrong-backend", native("mongo", "capability-title")),
        QueryNativeCapabilityCase("missing-template", native("elasticsearch", "missing-template")),
    )

    private fun templates() = ElasticsearchNativeQueryTemplateRegistry(
        mapOf(
            "capability-title" to ElasticsearchNativeQueryTemplate { parameters ->
                Query.of { query ->
                    query.term { term ->
                        term.field("title.exact")
                            .value((parameters.getValue("title") as QueryValue.StringValue).value)
                    }
                }
            },
        ),
    )

    private fun native(backendId: String, templateId: String): NativeExpression = NativeExpression(
        capabilityId = capabilityId,
        backendId = backendId,
        templateId = templateId,
        parameters = mapOf("title" to QueryValue.StringValue("Alpha.*")),
        declaredFields = setOf(LogicalField("title")),
    )
}

private class ElasticsearchCapabilityRequestMonitor {
    private val requests = ConcurrentHashMap<String, AtomicLong>()

    fun record(method: String, requestUri: String) {
        val path = requestUri.substringBefore('?')
        val operation = when {
            path.endsWith("/_mapping") -> "mapping"
            path.endsWith("/_settings") -> "settings"
            path.endsWith("/_search") -> "search"
            path.endsWith("/_count") -> "count"
            path.endsWith("/_pit") && method == "POST" -> "open"
            path.endsWith("/_pit") && method == "DELETE" -> "close"
            method == "HEAD" -> "exists"
            else -> "$method $path"
        }
        requests.computeIfAbsent(operation) { AtomicLong() }.incrementAndGet()
    }

    fun snapshot(): Map<String, Long> = requests.mapValues { (_, count) -> count.get() }

    fun reset() {
        requests.clear()
    }
}

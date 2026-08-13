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
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.elasticsearch.ElasticsearchSearchResponseGate
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.query.backend.PortableQueryDataset
import me.ahoo.wow.tck.query.backend.QueryCapabilityContract
import me.ahoo.wow.tck.query.backend.QueryCapabilityFixture
import me.ahoo.wow.tck.query.backend.QueryNativeCapabilityCase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.test.StepVerifier

class ElasticsearchQueryCapabilityContractTest {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture("es_query_capability")

    private lateinit var client: ReactiveElasticsearchClient
    private lateinit var portableFixture: ElasticsearchPortableQueryBackendFixture

    @BeforeEach
    fun prepareCapabilityIndex() {
        val gate = ElasticsearchSearchResponseGate()
        client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch, gate)
        portableFixture = ElasticsearchPortableQueryBackendFixture(client, QueryDocumentKind.SNAPSHOT, gate)
        StepVerifier.create(portableFixture.prepare(PortableQueryDataset)).verifyComplete()
        portableFixture.backendFactory.reset()
    }

    @AfterEach
    fun clearCapabilityIndex() {
        StepVerifier.create(portableFixture.clear()).verifyComplete()
    }

    @TestFactory
    fun elasticsearchFullTextObeysSharedCapabilityContract() = QueryCapabilityContract(
        ElasticsearchFullTextCapabilityFixture(client, portableFixture.backendFactory),
    ).dynamicTests()

    @TestFactory
    fun elasticsearchNativeObeysSharedCapabilityContract() = QueryCapabilityContract(
        ElasticsearchNativeCapabilityFixture(client, portableFixture.backendFactory),
    ).dynamicTests()
}

private abstract class ElasticsearchCapabilityFixture(
    protected val client: ReactiveElasticsearchClient,
    private val observer: ElasticsearchObservableQueryBackendFactory,
) : QueryCapabilityFixture {
    final override val target = PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT)
    open override val schema = PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT)
    final override val rawCommandCount: Long
        get() = observer.subscriptionCount(ElasticsearchQueryOperation.COUNT)

    final override fun reset() {
        observer.reset()
    }
}

private class ElasticsearchFullTextCapabilityFixture(
    client: ReactiveElasticsearchClient,
    observer: ElasticsearchObservableQueryBackendFactory,
) : ElasticsearchCapabilityFixture(client, observer) {
    override val id: String = "elasticsearch-full-text"
    override val capabilityId = QueryCapabilityId(ElasticsearchQueryBackendFactory.FULL_TEXT_CAPABILITY)
    override val expression = FullTextExpression(capabilityId, "你好", setOf(PortableQueryDataset.TITLE))
    override val backendFactory = ElasticsearchQueryBackendFactory(client)
}

private class ElasticsearchNativeCapabilityFixture(
    client: ReactiveElasticsearchClient,
    observer: ElasticsearchObservableQueryBackendFactory,
) : ElasticsearchCapabilityFixture(client, observer) {
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

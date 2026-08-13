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

import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch.indices.ExistsRequest
import co.elastic.clients.elasticsearch.indices.GetIndicesSettingsRequest
import co.elastic.clients.elasticsearch.indices.GetIndicesSettingsResponse
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.GetMappingResponse
import co.elastic.clients.json.JsonData
import co.elastic.clients.transport.endpoints.BooleanResponse
import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchQueryPresenceEncoder
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendReadinessReason
import me.ahoo.wow.query.schema.QueryCollectionKind
import me.ahoo.wow.query.schema.QueryFieldValueKind
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchIndicesClient
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class ElasticsearchIntegerQueryReadinessTest {
    @Test
    fun `authoritative system version accepts Elasticsearch integer and long mappings`() {
        listOf(
            Property.of { it.integer { value -> value } },
            Property.of { it.long_ { value -> value } },
        ).forEach { integral ->
            StepVerifier.create(readiness(integral, path = "version", system = true).inspect())
                .expectNext(QueryBackendReadiness.Ready)
                .verifyComplete()
        }

        StepVerifier.create(
            readiness(Property.of { it.double_ { value -> value } }, path = "version", system = true).inspect(),
        )
            .expectNext(QueryBackendReadiness.NotReady(QueryBackendReadinessReason.MAPPING_INCOMPATIBLE))
            .verifyComplete()
    }

    @Test
    fun `ordinary integer schema requires Elasticsearch long mapping`() {
        StepVerifier.create(
            readiness(Property.of { it.integer { value -> value } }, path = "rank", system = false).inspect(),
        ).expectNext(QueryBackendReadiness.NotReady(QueryBackendReadinessReason.MAPPING_INCOMPATIBLE))
            .verifyComplete()
        StepVerifier.create(
            readiness(Property.of { it.long_ { value -> value } }, path = "rank", system = false).inspect(),
        ).expectNext(QueryBackendReadiness.Ready).verifyComplete()
    }

    @Test
    fun `system flag alone cannot allow integer mapping outside authoritative version`() {
        StepVerifier.create(
            readiness(Property.of { it.integer { value -> value } }, path = "rank", system = true).inspect(),
        ).expectNext(QueryBackendReadiness.NotReady(QueryBackendReadinessReason.MAPPING_INCOMPATIBLE))
            .verifyComplete()
    }

    private fun readiness(property: Property, path: String, system: Boolean): ElasticsearchQueryReadiness {
        val client = mockk<ReactiveElasticsearchClient>()
        val indices = mockk<ReactiveElasticsearchIndicesClient>()
        every { client.indices() } returns indices
        every { indices.exists(any<ExistsRequest>()) } returns Mono.just(BooleanResponse(true))
        every { indices.getMapping(any<GetMappingRequest>()) } returns Mono.just(mapping(path, property))
        every { indices.getSettings(any<GetIndicesSettingsRequest>()) } returns Mono.just(indexSettings())
        return ElasticsearchQueryReadiness(client, "index", requirements(path, system))
    }

    private fun requirements(path: String, system: Boolean) = ElasticsearchQueryReadinessRequirements(
        configurationValid = true,
        fields = setOf(
            ElasticsearchMappingUsage.SOURCE,
            ElasticsearchMappingUsage.EXACT,
            ElasticsearchMappingUsage.SORT,
        ).mapTo(LinkedHashSet()) { usage ->
            ElasticsearchMappingFieldRequirement(
                path = path,
                valueKind = QueryFieldValueKind.INTEGER,
                collectionKind = QueryCollectionKind.NONE,
                system = system,
                usage = usage,
            )
        },
        presenceVersion = ElasticsearchQueryPresenceEncoder.VERSION,
    )

    private fun mapping(path: String, property: Property): GetMappingResponse = GetMappingResponse.of { response ->
        response.mappings("index") { record ->
            record.mappings { type ->
                type.meta(PRESENCE_VERSION_META, JsonData.of(ElasticsearchQueryPresenceEncoder.VERSION))
                    .properties(path, property)
            }
        }
    }

    private fun indexSettings(): GetIndicesSettingsResponse = GetIndicesSettingsResponse.of { response ->
        response.settings("index") { state -> state.settings { settings -> settings } }
    }

    companion object {
        private const val PRESENCE_VERSION_META = "wow_query_presence_version"
    }
}

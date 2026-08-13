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

import co.elastic.clients.elasticsearch._types.mapping.DynamicTemplate
import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch.indices.ExistsRequest
import co.elastic.clients.elasticsearch.indices.GetIndicesSettingsRequest
import co.elastic.clients.elasticsearch.indices.GetIndicesSettingsResponse
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.GetMappingResponse
import co.elastic.clients.json.JsonData
import co.elastic.clients.transport.endpoints.BooleanResponse
import co.elastic.clients.util.NamedValue
import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchQueryPresenceEncoder
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendReadinessReason
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchIndicesClient
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class ElasticsearchManagedPresenceReadinessTest {
    @Test
    fun `managed dynamic presence contract covers an unmaterialized deep marker`() {
        val requirements = requirements(
            "__wow_query.present",
            "__wow_query.null",
            "payload.__wow_query.present",
            "payload.__wow_query.null",
        )
        val mapping = mapping(
            templates = managedPresenceTemplates(),
            presenceTemplateVersion = ElasticsearchQueryPresenceEncoder.VERSION,
        )

        StepVerifier.create(readiness(requirements, mapping).inspect())
            .expectNext(QueryBackendReadiness.Ready)
            .verifyComplete()
    }

    @Test
    fun `unmaterialized marker requires the exact managed template contract`() {
        val requirements = requirements("payload.__wow_query.null")
        val validTemplates = managedPresenceTemplates()
        val invalidMappings = listOf(
            mapping(validTemplates, presenceTemplateVersion = null),
            mapping(validTemplates.reversed()),
            mapping(listOf(genericStringTemplate()) + validTemplates),
            mapping(
                listOf(
                    validTemplates.first(),
                    presenceTemplate("wow_query_null_keyword", "missing"),
                ),
            ),
        )

        invalidMappings.forEach { mapping ->
            StepVerifier.create(readiness(requirements, mapping).inspect())
                .expectNext(QueryBackendReadiness.NotReady(QueryBackendReadinessReason.MAPPING_INCOMPATIBLE))
                .verifyComplete()
        }
    }

    private fun requirements(vararg presenceFields: String) = ElasticsearchQueryReadinessRequirements(
        configurationValid = true,
        fields = emptySet(),
        presenceVersion = ElasticsearchQueryPresenceEncoder.VERSION,
        presenceFields = presenceFields.toSet(),
    )

    private fun readiness(
        requirements: ElasticsearchQueryReadinessRequirements,
        mapping: GetMappingResponse,
    ): ElasticsearchQueryReadiness {
        val client = mockk<ReactiveElasticsearchClient>()
        val indices = mockk<ReactiveElasticsearchIndicesClient>()
        every { client.indices() } returns indices
        every { indices.exists(any<ExistsRequest>()) } returns Mono.just(BooleanResponse(true))
        every { indices.getMapping(any<GetMappingRequest>()) } returns Mono.just(mapping)
        every { indices.getSettings(any<GetIndicesSettingsRequest>()) } returns Mono.just(indexSettings())
        return ElasticsearchQueryReadiness(client, "index", requirements)
    }

    private fun indexSettings(): GetIndicesSettingsResponse =
        GetIndicesSettingsResponse.of { response ->
            response.settings("index") { state -> state.settings { settings -> settings } }
        }

    private fun mapping(
        templates: List<NamedValue<DynamicTemplate>>,
        presenceTemplateVersion: Int? = ElasticsearchQueryPresenceEncoder.VERSION,
    ): GetMappingResponse = GetMappingResponse.of { response ->
        response.mappings("index") { record ->
            record.mappings { type ->
                type.meta(
                    ElasticsearchQueryReadiness.PRESENCE_VERSION_META,
                    JsonData.of(ElasticsearchQueryPresenceEncoder.VERSION),
                ).properties(
                    mapOf("payload" to Property.of { it.`object` { value -> value } }),
                ).dynamicTemplates(templates).apply {
                    if (presenceTemplateVersion != null) {
                        meta(
                            ElasticsearchQueryReadiness.PRESENCE_TEMPLATE_VERSION_META,
                            JsonData.of(presenceTemplateVersion),
                        )
                    }
                }
            }
        }
    }

    private fun managedPresenceTemplates() = listOf(
        presenceTemplate("wow_query_present_keyword", "present"),
        presenceTemplate("wow_query_null_keyword", "null"),
    )

    private fun genericStringTemplate() = NamedValue.of(
        "generic_string",
        DynamicTemplate.of { template ->
            template.matchMappingType("string")
                .mapping { property -> property.keyword { keyword -> keyword } }
        },
    )

    private fun presenceTemplate(
        name: String,
        marker: String,
    ) = NamedValue.of(
        name,
        DynamicTemplate.of { template ->
            template.pathMatch("__wow_query.$marker", "*.__wow_query.$marker")
                .matchMappingType("string")
                .mapping { property ->
                    property.keyword { keyword -> keyword.index(true).docValues(true) }
                }
        },
    )
}

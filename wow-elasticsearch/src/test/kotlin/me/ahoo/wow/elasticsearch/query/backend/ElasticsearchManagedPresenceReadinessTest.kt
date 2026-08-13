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

import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping
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
            rootMetadata = metadataProperty(),
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
            mapping(validTemplates, presenceTemplateVersion = null, rootMetadata = metadataProperty()),
            mapping(validTemplates.reversed(), rootMetadata = metadataProperty()),
            mapping(listOf(genericStringTemplate()) + validTemplates, rootMetadata = metadataProperty()),
            mapping(
                listOf(
                    validTemplates.first(),
                    presenceTemplate("wow_query_null_keyword", "missing"),
                ),
                rootMetadata = metadataProperty(),
            ),
        )

        invalidMappings.forEach { mapping ->
            StepVerifier.create(readiness(requirements, mapping).inspect())
                .expectNext(QueryBackendReadiness.NotReady(QueryBackendReadinessReason.MAPPING_INCOMPATIBLE))
                .verifyComplete()
        }
    }

    @Test
    fun `unmaterialized marker requires complete managed root properties`() {
        val requirements = requirements("payload.__wow_query.null")
        val invalidMappings = listOf(
            mapping(managedPresenceTemplates()),
            mapping(managedPresenceTemplates(), rootMetadata = metadataProperty(includeNull = false)),
            mapping(
                managedPresenceTemplates(),
                rootMetadata = metadataProperty(
                    present = keywordProperty(docValues = false),
                ),
            ),
            mapping(
                managedPresenceTemplates(),
                rootMetadata = metadataProperty(
                    nullProperty = Property.of { it.keyword { keyword -> keyword.normalizer("folding") } },
                ),
            ),
            mapping(
                managedPresenceTemplates(),
                rootMetadata = metadataProperty(
                    present = keywordProperty(index = false),
                ),
            ),
            mapping(
                managedPresenceTemplates(),
                rootMetadata = metadataProperty(
                    nullProperty = keywordProperty(nullValue = "missing"),
                ),
            ),
            mapping(
                managedPresenceTemplates(),
                rootMetadata = metadataProperty(
                    present = keywordProperty(ignoreAbove = 256),
                ),
            ),
            mapping(
                managedPresenceTemplates(),
                rootMetadata = metadataProperty(enabled = false),
            ),
        )

        assertNotReady(requirements, invalidMappings)
    }

    @Test
    fun `unmaterialized marker requires complete managed template keyword semantics`() {
        val requirements = requirements("payload.__wow_query.null")
        val templates = listOf(
            presenceTemplate("wow_query_present_keyword", "present"),
            presenceTemplate("wow_query_null_keyword", "null", docValues = false),
        )

        assertNotReady(
            requirements,
            listOf(mapping(templates, rootMetadata = metadataProperty())),
        )
    }

    @Test
    fun `unmaterialized marker rejects disabled or uncontrolled dynamic ancestors`() {
        listOf("state", "body", "payload").forEach { parent ->
            val requirements = requirements("$parent.__wow_query.null")
            val invalidMappings = buildList {
                listOf(DynamicMapping.False, DynamicMapping.Strict, DynamicMapping.Runtime).forEach { dynamic ->
                    add(
                        mapping(
                            managedPresenceTemplates(),
                            metadataProperty(),
                            rootDynamic = dynamic,
                            parentName = parent,
                        ),
                    )
                    add(
                        mapping(
                            managedPresenceTemplates(),
                            metadataProperty(),
                            parentName = parent,
                            payload = objectProperty(dynamic = dynamic),
                        ),
                    )
                }
                add(
                    mapping(
                        managedPresenceTemplates(),
                        metadataProperty(),
                        rootEnabled = false,
                        parentName = parent,
                    ),
                )
                add(
                    mapping(
                        managedPresenceTemplates(),
                        metadataProperty(),
                        parentName = parent,
                        payload = objectProperty(enabled = false),
                    ),
                )
                add(
                    mapping(
                        managedPresenceTemplates(),
                        metadataProperty(),
                        parentName = parent,
                        payload = Property.of { it.keyword { keyword -> keyword } },
                    ),
                )
            }

            assertNotReady(requirements, invalidMappings)
        }
    }

    @Test
    fun `unmaterialized marker rejects disabled or uncontrolled deep ancestor`() {
        val deepRequirements = requirements("payload.body.__wow_query.null")
        val deepMappings = listOf(DynamicMapping.False, DynamicMapping.Strict, DynamicMapping.Runtime).map { dynamic ->
            mapping(
                managedPresenceTemplates(),
                metadataProperty(),
                payload = objectProperty(
                    properties = mapOf("body" to objectProperty(dynamic = dynamic)),
                ),
            )
        } + mapping(
            managedPresenceTemplates(),
            metadataProperty(),
            payload = objectProperty(
                properties = mapOf("body" to objectProperty(enabled = false)),
            ),
        )

        assertNotReady(deepRequirements, deepMappings)
    }

    @Test
    fun `explicit marker rejects disabled root and root metadata object`() {
        assertNotReady(
            requirements("__wow_query.null"),
            listOf(
                mapping(
                    managedPresenceTemplates(),
                    metadataProperty(),
                    rootEnabled = false,
                ),
                mapping(
                    managedPresenceTemplates(),
                    metadataProperty(enabled = false),
                ),
            ),
        )
    }

    @Test
    fun `explicit marker rejects disabled object and nested source parents`() {
        listOf("state", "body", "payload").forEach { parent ->
            val explicitMetadata = mapOf("__wow_query" to metadataProperty())
            assertNotReady(
                requirements("$parent.__wow_query.null"),
                listOf(
                    mapping(
                        managedPresenceTemplates(),
                        metadataProperty(),
                        parentName = parent,
                        payload = objectProperty(enabled = false, properties = explicitMetadata),
                    ),
                    mapping(
                        managedPresenceTemplates(),
                        metadataProperty(),
                        parentName = parent,
                        payload = nestedProperty(enabled = false, properties = explicitMetadata),
                    ),
                ),
            )
        }
    }

    @Test
    fun `explicit marker rejects disabled multi level ancestor`() {
        val payload = objectProperty(
            properties = mapOf(
                "body" to objectProperty(
                    enabled = false,
                    properties = mapOf("__wow_query" to metadataProperty()),
                ),
            ),
        )

        assertNotReady(
            requirements("payload.body.__wow_query.null"),
            listOf(mapping(managedPresenceTemplates(), metadataProperty(), payload = payload)),
        )
    }

    private fun assertNotReady(
        requirements: ElasticsearchQueryReadinessRequirements,
        mappings: List<GetMappingResponse>,
    ) {
        mappings.forEach { mapping ->
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
        rootMetadata: Property? = null,
        presenceTemplateVersion: Int? = ElasticsearchQueryPresenceEncoder.VERSION,
        rootDynamic: DynamicMapping? = null,
        rootEnabled: Boolean? = null,
        parentName: String = "payload",
        payload: Property = objectProperty(),
    ): GetMappingResponse = GetMappingResponse.of { response ->
        response.mappings("index") { record ->
            record.mappings { type ->
                type.meta(
                    ElasticsearchQueryReadiness.PRESENCE_VERSION_META,
                    JsonData.of(ElasticsearchQueryPresenceEncoder.VERSION),
                ).properties(
                    buildMap {
                        put(parentName, payload)
                        if (rootMetadata != null) put("__wow_query", rootMetadata)
                    },
                ).dynamicTemplates(templates).apply {
                    if (rootDynamic != null) dynamic(rootDynamic)
                    if (rootEnabled != null) enabled(rootEnabled)
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
        docValues: Boolean = true,
    ) = NamedValue.of(
        name,
        DynamicTemplate.of { template ->
            template.pathMatch("__wow_query.$marker", "*.__wow_query.$marker")
                .matchMappingType("string")
                .mapping { property ->
                    property.keyword { keyword -> keyword.index(true).docValues(docValues) }
                }
        },
    )

    private fun metadataProperty(
        includeNull: Boolean = true,
        present: Property = keywordProperty(),
        nullProperty: Property = keywordProperty(),
        enabled: Boolean? = null,
    ): Property = Property.of { property ->
        property.`object` { objectProperty ->
            objectProperty.properties("present", present).apply {
                if (includeNull) properties("null", nullProperty)
                if (enabled != null) enabled(enabled)
            }
        }
    }

    private fun keywordProperty(
        index: Boolean = true,
        docValues: Boolean = true,
        nullValue: String? = null,
        ignoreAbove: Int? = null,
    ): Property = Property.of { property ->
        property.keyword { keyword ->
            keyword.index(index).docValues(docValues).apply {
                if (nullValue != null) nullValue(nullValue)
                if (ignoreAbove != null) ignoreAbove(ignoreAbove)
            }
        }
    }

    private fun objectProperty(
        dynamic: DynamicMapping? = null,
        enabled: Boolean? = null,
        properties: Map<String, Property> = emptyMap(),
    ): Property = Property.of { property ->
        property.`object` { objectProperty ->
            objectProperty.apply {
                if (dynamic != null) dynamic(dynamic)
                if (enabled != null) enabled(enabled)
                if (properties.isNotEmpty()) properties(properties)
            }
        }
    }

    private fun nestedProperty(
        enabled: Boolean? = null,
        properties: Map<String, Property> = emptyMap(),
    ): Property = Property.of { property ->
        property.nested { nestedProperty ->
            nestedProperty.apply {
                if (enabled != null) enabled(enabled)
                if (properties.isNotEmpty()) properties(properties)
            }
        }
    }
}

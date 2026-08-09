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

@file:OptIn(
    me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.elasticsearch.query.lifecycle

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.cluster.PutComponentTemplateRequest
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import co.elastic.clients.elasticsearch.indices.GetAliasRequest
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.IndexSettings
import co.elastic.clients.elasticsearch.indices.PutIndexTemplateRequest
import co.elastic.clients.elasticsearch.indices.UpdateAliasesRequest
import co.elastic.clients.elasticsearch.indices.get_alias.IndexAliases
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord
import co.elastic.clients.json.JsonData
import me.ahoo.wow.query.backend.SchemaContractId
import me.ahoo.wow.query.gateway.QueryDocumentKind
import org.springframework.data.elasticsearch.RestStatusException
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import java.time.Clock
import java.util.LinkedHashMap
import java.util.Optional

internal class ElasticsearchVersionedIndexTemplate(
    val manifest: ElasticsearchIndexMigrationManifest,
    val mapping: TypeMapping,
    val settings: IndexSettings? = null,
) {
    val componentTemplateName = "${manifest.names.alias.value}-query-mapping-${manifest.mappingVersion.tag}"
    val indexTemplateName = "${manifest.names.alias.value}-query-template-${manifest.mappingVersion.tag}"
    val indexPattern = "${manifest.names.alias.value}-${manifest.mappingVersion.tag}-*"

    init {
        requireManagedName(componentTemplateName, "Elasticsearch component template")
        requireManagedName(indexTemplateName, "Elasticsearch index template")
        mapping.toAttestation(manifest.id, manifest.names.physical).requireMatches(manifest)
    }

    fun componentRequest(): PutComponentTemplateRequest = PutComponentTemplateRequest.of { request ->
        request.name(componentTemplateName)
            .version(manifest.mappingVersion.value.toLong())
            .create(false)
            .meta(templateMetadata())
            .template { template ->
                template.mappings(mapping).also { builder -> settings?.let(builder::settings) }
            }
    }

    fun indexTemplateRequest(): PutIndexTemplateRequest = PutIndexTemplateRequest.of { request ->
        request.name(indexTemplateName)
            .version(manifest.mappingVersion.value.toLong())
            .create(false)
            .allowAutoCreate(false)
            .priority(INDEX_TEMPLATE_PRIORITY)
            .indexPatterns(indexPattern)
            .composedOf(componentTemplateName)
            .meta(templateMetadata())
    }

    private fun templateMetadata(): Map<String, JsonData> = linkedMapOf(
        ELASTICSEARCH_MAPPING_VERSION_META to JsonData.of(manifest.mappingVersion.tag),
        ELASTICSEARCH_DOCUMENT_KIND_META to JsonData.of(manifest.target.documentKind.name),
        ELASTICSEARCH_SCHEMA_CONTRACT_META to JsonData.of(manifest.schemaContractId.value),
        ELASTICSEARCH_CAPABILITY_DIGEST_META to JsonData.of(manifest.capabilityDigest.value),
    )
}

internal fun interface ElasticsearchVersionedIndexTemplateProvider {
    fun get(manifest: ElasticsearchIndexMigrationManifest): Mono<ElasticsearchVersionedIndexTemplate>
}

internal interface ElasticsearchIndexAdminClient {
    fun inspect(manifest: ElasticsearchIndexMigrationManifest): Mono<ElasticsearchIndexInventory>

    fun ensureTemplates(template: ElasticsearchVersionedIndexTemplate): Mono<Void>

    fun create(
        manifest: ElasticsearchIndexMigrationManifest,
        template: ElasticsearchVersionedIndexTemplate,
    ): Mono<ElasticsearchIndexAttestation>

    fun compareAndSetAlias(
        manifest: ElasticsearchIndexMigrationManifest,
        expected: ElasticsearchPhysicalIndex,
        current: ElasticsearchPhysicalIndex,
    ): Mono<ElasticsearchAliasTransition>
}

internal class ReactiveElasticsearchIndexAdminClient(
    private val client: ReactiveElasticsearchClient,
    private val clock: Clock,
) : ElasticsearchIndexAdminClient {
    override fun inspect(manifest: ElasticsearchIndexMigrationManifest): Mono<ElasticsearchIndexInventory> =
        Mono.zip(inspectAlias(manifest), inspectMappings(manifest))
            .map { tuple ->
                ElasticsearchIndexInventory(
                    manifest.names.alias,
                    tuple.t1.orElse(null),
                    tuple.t2,
                    clock.instant(),
                )
            }

    override fun ensureTemplates(template: ElasticsearchVersionedIndexTemplate): Mono<Void> =
        client.cluster().putComponentTemplate(template.componentRequest())
            .switchIfEmpty(operationFailed(template.manifest, "Component template returned no acknowledgement."))
            .flatMap { response ->
                if (!response.acknowledged()) {
                    operationFailed(template.manifest, "Component template was not acknowledged.")
                } else {
                    client.indices().putIndexTemplate(template.indexTemplateRequest())
                }
            }
            .switchIfEmpty(operationFailed(template.manifest, "Index template returned no acknowledgement."))
            .flatMap { response ->
                requireAcknowledged(
                    response.acknowledged(),
                    template.manifest,
                    "Index template was not acknowledged.",
                )
            }

    override fun create(
        manifest: ElasticsearchIndexMigrationManifest,
        template: ElasticsearchVersionedIndexTemplate,
    ): Mono<ElasticsearchIndexAttestation> {
        require(template.manifest == manifest) { "Elasticsearch versioned template must match the migration manifest." }
        return ensureTemplates(template).then(inspectExact(manifest, manifest.names.physical)).flatMap { existing ->
            if (existing.isPresent) {
                requireAttestation(manifest, existing.get())
            } else {
                client.indices().create(
                    CreateIndexRequest.of { request -> request.index(manifest.names.physical.value) },
                )
                    .switchIfEmpty(operationFailed(manifest, "Create index returned no acknowledgement."))
                    .flatMap { response ->
                        if (
                            !response.acknowledged() ||
                            !response.shardsAcknowledged() ||
                            response.index() != manifest.names.physical.value
                        ) {
                            operationFailed(manifest, "Create index was not fully acknowledged.")
                        } else {
                            inspectExact(manifest, manifest.names.physical).flatMap { created ->
                                if (created.isEmpty) {
                                    operationFailed(manifest, "Created index returned no mapping attestation.")
                                } else {
                                    requireAttestation(manifest, created.get())
                                }
                            }
                        }
                    }
            }
        }
    }

    override fun compareAndSetAlias(
        manifest: ElasticsearchIndexMigrationManifest,
        expected: ElasticsearchPhysicalIndex,
        current: ElasticsearchPhysicalIndex,
    ): Mono<ElasticsearchAliasTransition> = inspectAlias(manifest).flatMap { observedValue ->
        val observed = observedValue.orElse(null)
        if (observed == current) {
            return@flatMap Mono.just(
                ElasticsearchAliasTransition(manifest.names.alias, expected, current, clock.instant()),
            )
        }
        if (observed != expected) {
            return@flatMap lifecycleError(
                ElasticsearchIndexLifecycleErrorCode.ALIAS_CONFLICT,
                manifest,
                "Elasticsearch alias does not match the expected source generation.",
            )
        }
        val request = aliasTransitionRequest(manifest, expected, current)
        client.indices().updateAliases(request)
            .switchIfEmpty(operationFailed(manifest, "Alias transition returned no acknowledgement."))
            .flatMap { response ->
                if (!response.acknowledged()) {
                    operationFailed(manifest, "Alias transition was not acknowledged.")
                } else {
                    inspectAlias(manifest).flatMap { updated ->
                        if (updated.orElse(null) != current) {
                            lifecycleError(
                                ElasticsearchIndexLifecycleErrorCode.ALIAS_CONFLICT,
                                manifest,
                                "Elasticsearch alias did not converge on the requested generation.",
                            )
                        } else {
                            Mono.just(
                                ElasticsearchAliasTransition(
                                    manifest.names.alias,
                                    expected,
                                    current,
                                    clock.instant(),
                                ),
                            )
                        }
                    }
                }
            }
    }

    private fun inspectAlias(
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<Optional<ElasticsearchPhysicalIndex>> = client.indices().getAlias(
        GetAliasRequest.of { request ->
            request.name(manifest.names.alias.value).allowNoIndices(true).ignoreUnavailable(true)
        },
    ).map { response -> response.aliases() }
        .onErrorResume(::isNotFound) { Mono.just(emptyMap()) }
        .switchIfEmpty(Mono.just(emptyMap()))
        .map { aliases -> Optional.ofNullable(aliases.requireSingleWriteAlias(manifest)) }

    private fun inspectMappings(
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<Map<ElasticsearchPhysicalIndex, ElasticsearchIndexAttestation>> = client.indices().getMapping(
        GetMappingRequest.of { request ->
            request.index("${manifest.names.alias.value}-v*").allowNoIndices(true).ignoreUnavailable(true)
        },
    ).map { response -> response.mappings() }
        .onErrorResume(::isNotFound) { Mono.just(emptyMap()) }
        .switchIfEmpty(Mono.just(emptyMap()))
        .map { mappings -> mappings.toAttestations(manifest) }

    private fun inspectExact(
        manifest: ElasticsearchIndexMigrationManifest,
        physical: ElasticsearchPhysicalIndex,
    ): Mono<Optional<ElasticsearchIndexAttestation>> = client.indices().getMapping(
        GetMappingRequest.of { request ->
            request.index(physical.value).allowNoIndices(true).ignoreUnavailable(true)
        },
    ).map { response -> response.mappings() }
        .onErrorResume(::isNotFound) { Mono.just(emptyMap()) }
        .switchIfEmpty(Mono.just(emptyMap()))
        .map { mappings ->
            if (mappings.isEmpty()) {
                Optional.empty()
            } else {
                if (mappings.size != 1 || physical.value !in mappings) {
                    reject(
                        ElasticsearchIndexLifecycleErrorCode.ATTESTATION_MISMATCH,
                        manifest.id,
                        "Exact Elasticsearch index lookup returned another generation.",
                    )
                }
                Optional.of(mappings.getValue(physical.value).mapping().toAttestation(manifest.id, physical))
            }
        }

    private fun requireAttestation(
        manifest: ElasticsearchIndexMigrationManifest,
        actual: ElasticsearchIndexAttestation,
    ): Mono<ElasticsearchIndexAttestation> {
        actual.requireMatches(manifest)
        return Mono.just(actual)
    }
}

internal fun aliasTransitionRequest(
    manifest: ElasticsearchIndexMigrationManifest,
    expected: ElasticsearchPhysicalIndex,
    current: ElasticsearchPhysicalIndex,
): UpdateAliasesRequest = UpdateAliasesRequest.of { request ->
    request.actions { action ->
        action.remove { remove ->
            remove.index(expected.value).alias(manifest.names.alias.value).mustExist(true)
        }
    }.actions { action ->
        action.add { add ->
            add.index(current.value).alias(manifest.names.alias.value).isWriteIndex(true)
        }
    }
}

private fun Map<String, IndexAliases>.requireSingleWriteAlias(
    manifest: ElasticsearchIndexMigrationManifest,
): ElasticsearchPhysicalIndex? {
    if (isEmpty()) return null
    if (size != 1) {
        reject(
            ElasticsearchIndexLifecycleErrorCode.ALIAS_CONFLICT,
            manifest.id,
            "Managed Elasticsearch alias resolves to more than one physical index.",
        )
    }
    val (physical, aliases) = entries.single()
    val definition = aliases.aliases()[manifest.names.alias.value] ?: reject(
        ElasticsearchIndexLifecycleErrorCode.ALIAS_CONFLICT,
        manifest.id,
        "Elasticsearch alias response is missing the requested alias definition.",
    )
    if (!definition.isManagedWriteAlias()) {
        reject(
            ElasticsearchIndexLifecycleErrorCode.ALIAS_CONFLICT,
            manifest.id,
            "Managed Elasticsearch alias must be an unfiltered, unrouted write alias.",
        )
    }
    return ElasticsearchPhysicalIndex(physical)
}

private fun co.elastic.clients.elasticsearch.indices.AliasDefinition.isManagedWriteAlias(): Boolean =
    isWriteIndex() == true &&
        filter() == null &&
        indexRouting() == null &&
        searchRouting() == null &&
        routing() == null

private fun Map<String, IndexMappingRecord>.toAttestations(
    manifest: ElasticsearchIndexMigrationManifest,
): Map<ElasticsearchPhysicalIndex, ElasticsearchIndexAttestation> {
    val result = LinkedHashMap<ElasticsearchPhysicalIndex, ElasticsearchIndexAttestation>(size)
    entries.sortedBy(Map.Entry<String, IndexMappingRecord>::key).forEach { (index, record) ->
        val physical = ElasticsearchPhysicalIndex(index)
        result[physical] = record.mapping().toAttestation(manifest.id, physical)
    }
    return result
}

private fun IndexMappingRecord.mapping(): TypeMapping = mappings() ?: item() ?: error(
    "Elasticsearch mapping response has no mapping payload.",
)

private fun TypeMapping.toAttestation(
    migrationId: ElasticsearchIndexMigrationId,
    physical: ElasticsearchPhysicalIndex,
): ElasticsearchIndexAttestation {
    val mappingVersion = requiredMeta(migrationId, ELASTICSEARCH_MAPPING_VERSION_META)
    val versionNumber = mappingVersion.removePrefix("v").toIntOrNull() ?: reject(
        ElasticsearchIndexLifecycleErrorCode.ATTESTATION_MISMATCH,
        migrationId,
        "Elasticsearch mapping version metadata is invalid.",
    )
    val documentKind = runCatching {
        QueryDocumentKind.valueOf(requiredMeta(migrationId, ELASTICSEARCH_DOCUMENT_KIND_META))
    }.getOrElse {
        reject(
            ElasticsearchIndexLifecycleErrorCode.ATTESTATION_MISMATCH,
            migrationId,
            "Elasticsearch document kind metadata is invalid.",
        )
    }
    return try {
        ElasticsearchIndexAttestation(
            physical,
            ElasticsearchIndexMappingVersion(versionNumber),
            documentKind,
            SchemaContractId(requiredMeta(migrationId, ELASTICSEARCH_SCHEMA_CONTRACT_META)),
            ElasticsearchIndexCapabilityDigest(requiredMeta(migrationId, ELASTICSEARCH_CAPABILITY_DIGEST_META)),
        )
    } catch (error: IllegalArgumentException) {
        throw ElasticsearchIndexLifecycleException(
            ElasticsearchIndexLifecycleErrorCode.ATTESTATION_MISMATCH,
            migrationId,
            "Elasticsearch mapping metadata is invalid.",
            error,
        )
    }
}

private fun TypeMapping.requiredMeta(
    migrationId: ElasticsearchIndexMigrationId,
    key: String,
): String = meta()[key]?.let { value ->
    runCatching { value.to(String::class.java) }.getOrElse { error ->
        throw ElasticsearchIndexLifecycleException(
            ElasticsearchIndexLifecycleErrorCode.ATTESTATION_MISMATCH,
            migrationId,
            "Elasticsearch mapping metadata [$key] is unreadable.",
            error,
        )
    }
} ?: reject(
    ElasticsearchIndexLifecycleErrorCode.ATTESTATION_MISMATCH,
    migrationId,
    "Elasticsearch mapping metadata [$key] is missing.",
)

private fun ElasticsearchIndexAttestation.requireMatches(manifest: ElasticsearchIndexMigrationManifest) {
    if (this != manifest.destinationAttestation) {
        reject(
            ElasticsearchIndexLifecycleErrorCode.ATTESTATION_MISMATCH,
            manifest.id,
            "Elasticsearch mapping attestation does not match the migration manifest.",
        )
    }
}

private fun isNotFound(error: Throwable): Boolean = when (error) {
    is ElasticsearchException -> error.status() == NOT_FOUND
    is RestStatusException -> error.status == NOT_FOUND
    else -> false
}

private fun <T : Any> operationFailed(
    manifest: ElasticsearchIndexMigrationManifest,
    message: String,
): Mono<T> = lifecycleError(ElasticsearchIndexLifecycleErrorCode.OPERATION_FAILED, manifest, message)

private fun requireAcknowledged(
    acknowledged: Boolean,
    manifest: ElasticsearchIndexMigrationManifest,
    message: String,
): Mono<Void> = if (acknowledged) Mono.empty() else operationFailed(manifest, message)

private fun <T : Any> lifecycleError(
    code: ElasticsearchIndexLifecycleErrorCode,
    manifest: ElasticsearchIndexMigrationManifest,
    message: String,
): Mono<T> = Mono.error(ElasticsearchIndexLifecycleException(code, manifest.id, message))

private fun requireManagedName(value: String, label: String) {
    require(value.length <= MAX_MANAGED_NAME_LENGTH && value.matches(MANAGED_NAME_PATTERN)) {
        "$label is not a valid managed name."
    }
}

internal const val ELASTICSEARCH_MAPPING_VERSION_META = "wow_query_mapping_version"
internal const val ELASTICSEARCH_DOCUMENT_KIND_META = "wow_query_document_kind"
internal const val ELASTICSEARCH_SCHEMA_CONTRACT_META = "wow_query_schema_contract_id"
internal const val ELASTICSEARCH_CAPABILITY_DIGEST_META = "wow_query_capability_digest"
private const val INDEX_TEMPLATE_PRIORITY = 500L
private const val MAX_MANAGED_NAME_LENGTH = 255
private const val NOT_FOUND = 404
private val MANAGED_NAME_PATTERN = Regex("[a-z0-9][a-z0-9._-]*")

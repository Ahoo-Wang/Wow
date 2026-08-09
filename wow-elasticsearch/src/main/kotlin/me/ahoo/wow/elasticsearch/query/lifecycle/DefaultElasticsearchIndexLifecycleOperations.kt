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

package me.ahoo.wow.elasticsearch.query.lifecycle

import reactor.core.publisher.Mono

internal fun interface ElasticsearchAuthoritativeIndexRebuilder {
    fun rebuild(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<ElasticsearchIndexRebuildReceipt>
}

internal fun interface ElasticsearchIndexMigrationVerifier {
    fun verify(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
        physicalIndex: ElasticsearchPhysicalIndex,
    ): Mono<ElasticsearchIndexVerification>
}

internal class DefaultElasticsearchIndexLifecycleOperations(
    private val admin: ElasticsearchIndexAdminClient,
    private val templates: ElasticsearchVersionedIndexTemplateProvider,
    private val rebuilder: ElasticsearchAuthoritativeIndexRebuilder,
    private val verifier: ElasticsearchIndexMigrationVerifier,
) : ElasticsearchIndexLifecycleOperations {
    override fun validate(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<ElasticsearchIndexInventory> = admin.inspect(manifest)

    override fun create(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<ElasticsearchIndexAttestation> = templates.get(manifest).flatMap { template ->
        admin.create(manifest, template)
    }

    override fun rebuild(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<ElasticsearchIndexRebuildReceipt> = rebuilder.rebuild(command, manifest)

    override fun verify(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
        physicalIndex: ElasticsearchPhysicalIndex,
    ): Mono<ElasticsearchIndexVerification> = verifier.verify(command, manifest, physicalIndex)

    override fun cutover(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<ElasticsearchAliasTransition> = admin.compareAndSetAlias(
        manifest,
        manifest.sourcePhysicalIndex,
        manifest.names.physical,
    )

    override fun rollback(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<ElasticsearchAliasTransition> = admin.compareAndSetAlias(
        manifest,
        manifest.names.physical,
        manifest.sourcePhysicalIndex,
    )
}

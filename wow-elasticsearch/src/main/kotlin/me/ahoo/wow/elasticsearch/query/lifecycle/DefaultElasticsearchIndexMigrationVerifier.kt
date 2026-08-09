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
import java.time.Clock

internal data class ElasticsearchAuthoritativeVerificationSnapshot(
    val checksumAlgorithm: ElasticsearchIndexChecksumAlgorithm,
    val count: Long,
    val identityChecksum: ElasticsearchIndexChecksum,
    val contentChecksum: ElasticsearchIndexChecksum,
    val watermark: Long?,
) {
    init {
        require(count >= 0) { "Authoritative verification count must not be negative." }
        require(watermark == null || watermark >= 0) { "Authoritative watermark must not be negative." }
    }
}

internal data class ElasticsearchPhysicalIndexVerificationSnapshot(
    val checksumAlgorithm: ElasticsearchIndexChecksumAlgorithm,
    val physicalIndex: ElasticsearchPhysicalIndex,
    val count: Long,
    val identityChecksum: ElasticsearchIndexChecksum,
    val contentChecksum: ElasticsearchIndexChecksum,
    val versionContinuity: Boolean,
    val watermark: Long?,
) {
    init {
        require(count >= 0) { "Physical index verification count must not be negative." }
        require(watermark == null || watermark >= 0) { "Indexed watermark must not be negative." }
    }
}

internal data class ElasticsearchIndexProbeVerification(
    val recordMismatchCount: Long,
    val analyticsMismatchCount: Long,
) {
    init {
        require(recordMismatchCount >= 0 && analyticsMismatchCount >= 0) {
            "Elasticsearch query probe mismatch counts must not be negative."
        }
    }
}

internal fun interface ElasticsearchAuthoritativeVerificationSource {
    fun capture(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<ElasticsearchAuthoritativeVerificationSnapshot>
}

internal fun interface ElasticsearchPhysicalIndexVerificationSource {
    fun inspect(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
        physicalIndex: ElasticsearchPhysicalIndex,
    ): Mono<ElasticsearchPhysicalIndexVerificationSnapshot>
}

internal interface ElasticsearchIndexMigrationProbeVerifier {
    val suiteId: ElasticsearchIndexProbeSuiteId

    fun compare(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
        physicalIndex: ElasticsearchPhysicalIndex,
    ): Mono<ElasticsearchIndexProbeVerification>
}

/** Combines independently computed authority, physical-index and query-probe evidence into one immutable report. */
internal class DefaultElasticsearchIndexMigrationVerifier(
    private val authority: ElasticsearchAuthoritativeVerificationSource,
    private val physical: ElasticsearchPhysicalIndexVerificationSource,
    private val probes: ElasticsearchIndexMigrationProbeVerifier,
    private val clock: Clock,
) : ElasticsearchIndexMigrationVerifier {
    override fun verify(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
        physicalIndex: ElasticsearchPhysicalIndex,
    ): Mono<ElasticsearchIndexVerification> = Mono.defer {
        if (probes.suiteId != manifest.verificationContract.probeSuiteId) {
            reject(
                ElasticsearchIndexLifecycleErrorCode.VALIDATION_FAILED,
                manifest.id,
                "Elasticsearch query probe suite does not match the migration verification contract.",
            )
        }
        Mono.zip(
            Mono.defer { authority.capture(command, manifest) },
            Mono.defer { physical.inspect(command, manifest, physicalIndex) },
            Mono.defer { probes.compare(command, manifest, physicalIndex) },
        ).map { tuple ->
            val expected = tuple.t1
            val actual = tuple.t2
            val probe = tuple.t3
            if (expected.checksumAlgorithm != manifest.verificationContract.checksumAlgorithm) {
                reject(
                    ElasticsearchIndexLifecycleErrorCode.VERIFICATION_FAILED,
                    manifest.id,
                    "Authoritative checksum algorithm does not match the migration verification contract.",
                )
            }
            if (actual.checksumAlgorithm != manifest.verificationContract.checksumAlgorithm) {
                reject(
                    ElasticsearchIndexLifecycleErrorCode.VERIFICATION_FAILED,
                    manifest.id,
                    "Physical-index checksum algorithm does not match the migration verification contract.",
                )
            }
            ElasticsearchIndexVerification(
                actual.physicalIndex,
                expected.count,
                actual.count,
                expected.identityChecksum,
                actual.identityChecksum,
                expected.contentChecksum,
                actual.contentChecksum,
                actual.versionContinuity,
                expected.watermark,
                actual.watermark,
                probe.recordMismatchCount,
                probe.analyticsMismatchCount,
                clock.instant(),
            )
        }
    }
}

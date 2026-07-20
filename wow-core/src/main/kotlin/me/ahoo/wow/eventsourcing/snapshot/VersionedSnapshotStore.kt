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

package me.ahoo.wow.eventsourcing.snapshot

import me.ahoo.wow.api.modeling.AggregateId
import reactor.core.publisher.Mono

/**
 * Optional snapshot-store capability for immutable historical checkpoints.
 *
 * Historical checkpoints are deliberately separate from [SnapshotStore.save], whose contract
 * remains "save the latest snapshot". A checkpoint identified by aggregate and version is
 * immutable: repeated writes are idempotent and must not replace the first stored value.
 */
interface VersionedSnapshotStore : SnapshotStore {
    /**
     * Loads the greatest checkpoint version that is less than or equal to [maxVersion].
     *
     * Missing checkpoints complete empty. Backend, authentication, and timeout failures must be
     * propagated rather than converted to an empty result.
     */
    fun <S : Any> loadAtOrBefore(
        aggregateId: AggregateId,
        maxVersion: Int,
    ): Mono<Snapshot<S>>

    /**
     * Saves an immutable historical checkpoint without changing the latest snapshot.
     */
    fun <S : Any> saveCheckpoint(snapshot: Snapshot<S>): Mono<Void>
}

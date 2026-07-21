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

package me.ahoo.wow.opentelemetry.snapshot

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.VersionedSnapshotStore
import me.ahoo.wow.opentelemetry.ReactorTraceContext
import me.ahoo.wow.opentelemetry.TraceMono
import reactor.core.publisher.Mono

class TracingVersionedSnapshotStore(
    private val checkpointStore: VersionedSnapshotStore,
) : TracingSnapshotStore(checkpointStore),
    VersionedSnapshotStore {

    override fun <S : Any> loadAtOrBefore(
        aggregateId: AggregateId,
        maxVersion: Int,
    ): Mono<Snapshot<S>> {
        return Mono.deferContextual {
            val parentContext = ReactorTraceContext.get(it)
            val source = Mono.defer {
                checkpointStore.loadAtOrBefore<S>(aggregateId, maxVersion)
            }
            TraceMono(
                parentContext,
                SnapshotStoreInstrumenter.CHECKPOINT_LOAD_INSTRUMENTER,
                aggregateId,
                source,
            )
        }
    }

    override fun <S : Any> saveCheckpoint(snapshot: Snapshot<S>): Mono<Void> {
        return Mono.deferContextual {
            val parentContext = ReactorTraceContext.get(it)
            val source = Mono.defer {
                checkpointStore.saveCheckpoint(snapshot)
            }
            TraceMono(
                parentContext,
                SnapshotStoreInstrumenter.CHECKPOINT_SAVE_INSTRUMENTER,
                snapshot.aggregateId,
                source,
            )
        }
    }
}

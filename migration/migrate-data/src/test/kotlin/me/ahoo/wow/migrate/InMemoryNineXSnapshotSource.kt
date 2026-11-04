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

package me.ahoo.wow.migrate

import java.util.concurrent.CopyOnWriteArrayList

/** 9.x snapshots in memory, ordered as the MongoDB store reads them: by aggregate id; instances from [events]. */
internal class InMemoryNineXSnapshotSource(
    snapshots: Collection<NineXSnapshot> = emptyList(),
    private val events: NineXEventSource = InMemoryNineXEventSource(),
) : NineXSnapshotSource {
    private val snapshots = CopyOnWriteArrayList(snapshots)

    fun add(snapshot: NineXSnapshot) {
        snapshots += snapshot
    }

    /** Takes [snapshot] in place of the instance's, as 9.x writes a newer one over it. */
    fun put(snapshot: NineXSnapshot) {
        snapshots.removeIf { it.aggregate == snapshot.aggregate && it.aggregateId.id == snapshot.aggregateId.id }
        snapshots += snapshot
    }

    override fun snapshots(aggregate: NineXAggregate, after: NineXAggregateId?, limit: Int): List<NineXSnapshot> =
        snapshots.filter { it.aggregate == aggregate && (after == null || it.aggregateId.id > after.id) }
            .sortedBy { it.aggregateId.id }
            .take(limit)

    override fun snapshot(aggregate: NineXAggregate, id: String): NineXSnapshot? =
        snapshots.lastOrNull { it.aggregate == aggregate && it.aggregateId.id == id }

    override fun takenSince(aggregate: NineXAggregate, from: Long, after: String?, limit: Int): List<NineXSnapshot> =
        snapshots(aggregate, after?.let { NineXAggregateId(it, "") }, Int.MAX_VALUE)
            .filter { (it.snapshotTime ?: from) >= from }
            .take(limit)

    override fun instances(aggregate: NineXAggregate): Long =
        events.aggregateIds(aggregate, null, Int.MAX_VALUE).size.toLong()

    override fun deleted(aggregate: NineXAggregate): Long =
        snapshots.count { it.aggregate == aggregate && it.deleted }.toLong()
}

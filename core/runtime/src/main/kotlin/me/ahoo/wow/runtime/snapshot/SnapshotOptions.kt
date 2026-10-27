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

package me.ahoo.wow.runtime.snapshot

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The tuning of each context's [SnapshotMaintenance]: it runs every [interval]; an in-place rebuild
 * rewrites [batch] snapshots a page, at most [pages] pages a run, under a lease of [leaseTtl]; a node stays a member of
 * its state revision for [memberTtl] after its last run, so a rollback waits that long after the last node of the
 * higher revision is gone (none when it leaves gracefully). The snapshot subscriptions write at most [writeBatch]
 * snapshots of a partition with one [me.ahoo.wow.spi.snapshot.SnapshotStore.saveAll].
 *
 * Fields may be added: Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class SnapshotOptions @InternalWowApi constructor(
    val interval: Duration = DEFAULT_INTERVAL,
    val batch: Int = DEFAULT_BATCH,
    val pages: Int = DEFAULT_PAGES,
    val leaseTtl: Duration = DEFAULT_TTL,
    val memberTtl: Duration = DEFAULT_TTL,
    val writeBatch: Int = DEFAULT_WRITE_BATCH,
) {
    init {
        require(interval.isPositive()) { "The snapshot maintenance runs at a positive interval." }
        require(batch >= 1) { "A rebuild rewrites at least one snapshot a page." }
        require(pages >= 1) { "A rebuild rewrites at least one page a run." }
        require(leaseTtl > interval) { "The rebuild lease outlives the interval between runs." }
        require(memberTtl > interval) { "A revision membership outlives the interval between runs." }
        require(writeBatch >= 1) { "A snapshot write holds at least one snapshot." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is SnapshotOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "SnapshotOptions(interval=$interval, batch=$batch, pages=$pages, leaseTtl=$leaseTtl, " +
            "memberTtl=$memberTtl, writeBatch=$writeBatch)"

    private fun valueFields(): List<Any?> = listOf(interval, batch, pages, leaseTtl, memberTtl, writeBatch)

    companion object {
        val DEFAULT_INTERVAL: Duration = 5.seconds
        const val DEFAULT_BATCH: Int = 100
        const val DEFAULT_PAGES: Int = 10
        val DEFAULT_TTL: Duration = 30.seconds

        /** How many snapshots one write of a partition holds at most, by default. */
        const val DEFAULT_WRITE_BATCH: Int = 256
    }
}

/**
 * Whether the snapshots of an aggregate type are being rebuilt or backfilled: its primary target is
 * not caught up, so a query of them answers with `rebuilding`.
 */
@InternalWowApi
fun interface SnapshotStatus {
    fun rebuilding(type: AggregateType): Boolean

    @InternalWowApi
    companion object {
        /** Nothing is rebuilding. */
        @JvmField
        val NONE: SnapshotStatus = SnapshotStatus { false }
    }
}

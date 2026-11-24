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

package me.ahoo.wow.postgres

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.Ddl
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The settings of a [PostgresEventLog]: how its tails poll, and how it manages its tables. An immutable value with the
 * defaults a host starts from; the store builds it once from its configuration
 * ([PostgresStoreProvider][me.ahoo.wow.postgres.PostgresStoreProvider]), and the log takes it whole. The MongoDB log
 * takes its own, `MongoEventLogOptions`, of the same shape.
 */
@InternalWowApi
class PostgresEventLogOptions(
    /** How often a tail polls while it finds entries ([PostgresEventLog]'s polling). */
    val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    /**
     * The longest wait of an idle tail, which doubles its wait up to it: an entry another node appends is read within
     * it.
     */
    val maxPollInterval: Duration = DEFAULT_MAX_POLL_INTERVAL,
    /** How many entries a tail reads at a time. */
    val tailBatch: Int = DEFAULT_TAIL_BATCH,
    /** How the log's tables are managed: created when missing by default (`wow.ddl`). */
    val ddl: Ddl = Ddl.AUTO,
    /**
     * The streams above which an append across partitions binds each column as one array, one statement text whatever
     * its size ([PostgresBatches.ARRAYS_ABOVE]); 0 binds every such group so.
     */
    val arraysAbove: Int = PostgresBatches.ARRAYS_ABOVE,
    /**
     * How long a tail may read nothing while its partition's counter is past what it read before it warns, naming the
     * partition and the sequence it waits for, and again each time as long ([TailStall]): its entry's table is not in
     * the registry, or the entry was deleted.
     */
    val stallWarning: Duration = DEFAULT_STALL_WARNING,
) {
    init {
        require(pollInterval.isPositive()) { "The poll interval is positive." }
        require(maxPollInterval >= pollInterval) { "The longest poll interval is at least the poll interval." }
        require(tailBatch >= 1) { "A tail reads at least one entry at a time." }
        require(arraysAbove >= 0) { "The rows above which an append binds as arrays are never negative." }
        require(stallWarning.isPositive()) { "A stalled tail warns after a positive time." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is PostgresEventLogOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "PostgresEventLogOptions(pollInterval=$pollInterval, maxPollInterval=$maxPollInterval, " +
            "tailBatch=$tailBatch, ddl=$ddl, arraysAbove=$arraysAbove, stallWarning=$stallWarning)"

    private fun valueFields(): List<Any?> =
        listOf(pollInterval, maxPollInterval, tailBatch, ddl, arraysAbove, stallWarning)

    internal companion object {
        val DEFAULT_POLL_INTERVAL: Duration = 50.milliseconds
        val DEFAULT_MAX_POLL_INTERVAL: Duration = 1.seconds
        const val DEFAULT_TAIL_BATCH: Int = 500
        val DEFAULT_STALL_WARNING: Duration = 30.seconds
    }
}

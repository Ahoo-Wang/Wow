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

package me.ahoo.wow.mongo.log

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.mongo.MongoSharding
import me.ahoo.wow.mongo.MongoTimeouts
import me.ahoo.wow.spi.Ddl
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The settings of a [MongoEventLog]: how its tails poll, how long its calls may take, and how it manages and shards
 * its collections. An immutable value with the defaults a host starts from; the store builds it once from its
 * configuration ([MongoStoreProvider][me.ahoo.wow.mongo.MongoStoreProvider]), and the log takes it whole.
 */
@InternalWowApi
class MongoEventLogOptions(
    /** How often a tail polls while it finds entries ([MongoEventLog]'s polling). */
    val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    /**
     * The longest wait of an idle tail, which doubles its wait up to it: an entry another node appends is read within
     * it.
     */
    val maxPollInterval: Duration = DEFAULT_MAX_POLL_INTERVAL,
    /** How many sequences a tail reads past its last at a time. */
    val tailBatch: Int = DEFAULT_TAIL_BATCH,
    /**
     * How long an append's insert may take: a server that does not answer fails it, so a command appending during an
     * outage is answered before its wait ends, as unknown. Below the default wait timeout.
     */
    val appendTimeout: Duration = DEFAULT_APPEND_TIMEOUT,
    /** How long each of its calls may take (`wow.mongo.operation-timeout`, [MongoTimeouts]). */
    val operationTimeout: Duration = MongoTimeouts.OPERATION,
    /** How the log's collections and indexes are managed: created when missing by default (`wow.ddl`). */
    val ddl: Ddl = Ddl.AUTO,
    /** Whether the log shards its aggregate collections on a sharded cluster (the store's `sharding` option). */
    val sharding: MongoSharding = MongoSharding.AUTO,
    /**
     * How long a tail leaves a done block on the counter's list, read through, for its node's next reservation
     * ([DoneBlocks]); zero takes it out at once.
     */
    val doneGrace: Duration = DoneBlocks.GRACE,
) {
    init {
        require(pollInterval.isPositive()) { "The poll interval is positive." }
        require(maxPollInterval >= pollInterval) { "The longest poll interval is at least the poll interval." }
        require(tailBatch >= 1) { "A tail reads at least one sequence at a time." }
        require(appendTimeout.isPositive()) { "The append timeout is positive." }
        require(operationTimeout.isPositive()) { "The operation timeout is positive." }
        require(!doneGrace.isNegative()) { "The done blocks' grace is never negative." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is MongoEventLogOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "MongoEventLogOptions(pollInterval=$pollInterval, maxPollInterval=$maxPollInterval, tailBatch=$tailBatch, " +
            "appendTimeout=$appendTimeout, operationTimeout=$operationTimeout, ddl=$ddl, sharding=$sharding, " +
            "doneGrace=$doneGrace)"

    private fun valueFields(): List<Any?> =
        listOf(pollInterval, maxPollInterval, tailBatch, appendTimeout, operationTimeout, ddl, sharding, doneGrace)

    internal companion object {
        val DEFAULT_POLL_INTERVAL: Duration = 50.milliseconds
        val DEFAULT_MAX_POLL_INTERVAL: Duration = 1.seconds
        const val DEFAULT_TAIL_BATCH: Int = 500

        /** How long an append's insert may take by default: below the default wait timeout of 10 seconds. */
        val DEFAULT_APPEND_TIMEOUT: Duration = 5.seconds
    }
}

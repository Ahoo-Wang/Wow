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

package me.ahoo.wow.runtime.wait

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.WaitPlan
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The tuning of waits on one node; the node itself, as signals address it, is its [WaitCoordinator]'s origin.
 *
 * - [maxTimeout] caps the timeout a caller asks for.
 * - [maxHops] is the longest chain a wait may follow: [DEFAULT_MAX_HOPS] by default, at most
 *   [MAX_HOPS_CAP]. Each saga a chain follows adds two hops, its `handled` and the next `committed`.
 * - [unknownContexts] is what a chain may name of a context this node knows no Manifest of.
 * - [defaultTimeout] is the timeout of a command whose caller names no wait plan, at most [maxTimeout]: a default
 *   above it is refused, since it would act as the maximum without a word.
 *   [me.ahoo.wow.runtime.admission.CommandGateway] applies it to every entry.
 * - [maxRegistered] is how many waits the node holds at once: [DEFAULT_MAX_REGISTERED] by default. A
 *   command whose wait would be one more is not run: it is answered FAILED at ACCEPTED with
 *   [ResultCodes.BUSY] (`wow.command.busy`, 429 with `Retry-After`), as a command beyond the node's in-flight limit
 *   is, so a retry with the same request id is safe. A plan answered at ACCEPTED registers no wait
 *   and is never refused so.
 *
 * Fields may be added: Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class WaitOptions @InternalWowApi constructor(
    val maxTimeout: Duration = DEFAULT_MAX_TIMEOUT,
    val maxHops: Int = DEFAULT_MAX_HOPS,
    val defaultTimeout: Duration = WaitPlan.DEFAULT_TIMEOUT,
    val unknownContexts: UnknownContexts = UnknownContexts.ALLOW,
    val maxRegistered: Int = DEFAULT_MAX_REGISTERED,
) {
    init {
        require(maxTimeout.isPositive()) { "The maximum wait timeout is positive." }
        require(maxHops >= 1) { "A wait chain has at least one hop." }
        require(maxHops <= MAX_HOPS_CAP) { "A wait chain has at most $MAX_HOPS_CAP hops, not $maxHops." }
        require(defaultTimeout.isPositive()) { "The default wait timeout is positive." }
        require(defaultTimeout <= maxTimeout) {
            "The default wait timeout $defaultTimeout is above the maximum $maxTimeout: raise the maximum or lower " +
                "the default."
        }
        require(maxRegistered >= 1) { "A node holds at least one wait, not $maxRegistered." }
    }

    /** This WaitOptions with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        maxTimeout: Duration = this.maxTimeout,
        maxHops: Int = this.maxHops,
        defaultTimeout: Duration = this.defaultTimeout,
        unknownContexts: UnknownContexts = this.unknownContexts,
        maxRegistered: Int = this.maxRegistered,
    ): WaitOptions = WaitOptions(maxTimeout, maxHops, defaultTimeout, unknownContexts, maxRegistered)

    override fun equals(other: Any?): Boolean =
        this === other || other is WaitOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "WaitOptions(maxTimeout=$maxTimeout, maxHops=$maxHops, " +
            "defaultTimeout=$defaultTimeout, unknownContexts=$unknownContexts, maxRegistered=$maxRegistered)"

    private fun valueFields(): List<Any?> =
        listOf(maxTimeout, maxHops, defaultTimeout, unknownContexts, maxRegistered)

    companion object {
        val DEFAULT_MAX_TIMEOUT: Duration = 60.seconds
        const val DEFAULT_MAX_HOPS = 16

        /** The most hops a node lets a wait chain have, whatever it is configured with. */
        const val MAX_HOPS_CAP = 32

        /**
         * The waits a node holds at once by default: four times the commands it has in flight by
         * default ([me.ahoo.wow.runtime.InFlightLimit.DEFAULT_MAX]), since a wait outlives its command's execution
         * while it waits for the later hops (the default plan's snapshot).
         */
        const val DEFAULT_MAX_REGISTERED = 16_384
    }
}

/**
 * What the node a wait starts on does with a hop of a context it knows no Manifest of, its own or a peer's.
 */
enum class UnknownContexts {
    /**
     * The default: a context with a legal name is let through, and the hop is checked where the chain
     * arrives; a context or a subscription that is not there is never signalled, so the wait times out at the last
     * hop it reached.
     */
    ALLOW,

    /** A hop of a context the node does not know makes the plan invalid ([ResultCodes.WAIT_INVALID]). */
    REJECT,
}

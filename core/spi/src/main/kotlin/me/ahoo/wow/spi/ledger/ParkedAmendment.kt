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

package me.ahoo.wow.spi.ledger

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi

/** A change of a parked entry's retry state ([FailureLedger.amend]). */
@WowSpi
sealed interface ParkedAmendment {
    /**
     * Claims an automatic retry: when the entry is still at [revision], the one read, it has made one more retry,
     * started [at] (epoch milliseconds).
     *
     * Fields may be added: construct one with [of]; the constructor is internal to Wow.
     */
    class ClaimRetry @InternalWowApi constructor(val revision: Long, val at: Long) : ParkedAmendment {
        init {
            require(revision >= 0) { "A ledger revision is never negative." }
        }

        override fun equals(other: Any?): Boolean =
            this === other || other is ClaimRetry && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "ClaimRetry(revision=$revision, at=$at)"

        private fun valueFields(): List<Any?> = listOf(revision, at)

        companion object {
            /** A ClaimRetry of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(revision: Long, at: Long): ClaimRetry = ClaimRetry(revision, at)
        }
    }

    /**
     * Marks the entry [recoverable] (9.x `MarkRecoverable`).
     *
     * Fields may be added: construct one with [of]; the constructor is internal to Wow.
     */
    class MarkRecoverable @InternalWowApi constructor(val recoverable: Recoverable) : ParkedAmendment {
        override fun equals(other: Any?): Boolean =
            this === other || other is MarkRecoverable && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "MarkRecoverable(recoverable=$recoverable)"

        private fun valueFields(): List<Any?> = listOf(recoverable)

        companion object {
            /** A MarkRecoverable of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(recoverable: Recoverable): MarkRecoverable = MarkRecoverable(recoverable)
        }
    }

    /**
     * Applies [spec] to the entry, or the subscription's spec again when null (9.x `ApplyRetrySpec`).
     *
     * Fields may be added: construct one with [of]; the constructor is internal to Wow.
     */
    class ApplyRetrySpec @InternalWowApi constructor(val spec: ParkedRetrySpec?) : ParkedAmendment {
        override fun equals(other: Any?): Boolean =
            this === other || other is ApplyRetrySpec && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "ApplyRetrySpec(spec=$spec)"

        private fun valueFields(): List<Any?> = listOf(spec)

        companion object {
            /** An ApplyRetrySpec of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(spec: ParkedRetrySpec?): ApplyRetrySpec = ApplyRetrySpec(spec)
        }
    }

    /**
     * Leaves [request] for the partition's owner, replacing one it has not taken yet.
     *
     * Fields may be added: construct one with [of]; the constructor is internal to Wow.
     */
    class Request @InternalWowApi constructor(val request: LedgerRequest) : ParkedAmendment {
        override fun equals(other: Any?): Boolean =
            this === other || other is Request && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Request(request=$request)"

        private fun valueFields(): List<Any?> = listOf(request)

        companion object {
            /** A Request of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(request: LedgerRequest): Request = Request(request)
        }
    }

    /**
     * Records when the owner of the entry's partition next looks at it: when the entry is still at [revision], the one
     * the owner read, it is scheduled [at] (epoch milliseconds, [ParkedAggregate.NEVER] for an entry that is not
     * retried automatically). So a claim, a repark or an operator's change made since the owner read the entry is never
     * covered by a schedule computed before it.
     *
     * Fields may be added: construct one with [of]; the constructor is internal to Wow.
     */
    class Schedule @InternalWowApi constructor(val revision: Long, val at: Long) : ParkedAmendment {
        init {
            require(revision >= 0) { "A ledger revision is never negative." }
        }

        override fun equals(other: Any?): Boolean =
            this === other || other is Schedule && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Schedule(revision=$revision, at=$at)"

        private fun valueFields(): List<Any?> = listOf(revision, at)

        companion object {
            /** A Schedule of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(revision: Long, at: Long): Schedule = Schedule(revision, at)
        }
    }

    /**
     * Takes the operator's request the owner read: when the entry is still at [revision], the one read, it no longer
     * holds a request.
     *
     * Fields may be added: construct one with [of]; the constructor is internal to Wow.
     */
    class TakeRequest @InternalWowApi constructor(val revision: Long) : ParkedAmendment {
        init {
            require(revision >= 0) { "A ledger revision is never negative." }
        }

        override fun equals(other: Any?): Boolean =
            this === other || other is TakeRequest && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "TakeRequest(revision=$revision)"

        private fun valueFields(): List<Any?> = listOf(revision)

        companion object {
            /** A TakeRequest of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(revision: Long): TakeRequest = TakeRequest(revision)
        }
    }
}

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

package me.ahoo.wow.runtime.subscription

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec

/**
 * Hears what happens to a subscription's parked entries that an operator should know of (9.x's WeCom
 * webhook): an aggregate parked, and an entry whose automatic retries are spent. It is told on the node that parked
 * or retried the entry, once per change of the ledger, best effort: a notifier that fails is logged, and the
 * subscription goes on. A notifier should return quickly; it runs beside the subscription, not in its way.
 */
fun interface ParkedNotifier {
    suspend fun notify(notice: ParkedNotice)

    companion object {
        /** Tells nobody. */
        @JvmField
        val NONE: ParkedNotifier = ParkedNotifier { }

        /** Tells each of [notifiers] in turn, one that fails not keeping the others from hearing; [NONE] for none. */
        @JvmStatic
        fun of(notifiers: List<ParkedNotifier>): ParkedNotifier = when (notifiers.size) {
            0 -> NONE
            1 -> notifiers.single()
            else -> ParkedNotifier { notice -> notifiers.forEach { it.notifyQuietly(notice) } }
        }
    }
}

/**
 * What a [ParkedNotifier] hears: [kind] happened at [at] (epoch milliseconds) to the aggregate [ref], which the
 * subscription [subscription] of [context], run in lease [group], parked from its event at [fromVersion].
 *
 * It is a view of the failure ledger's entry, with what a notification says: why the aggregate parked ([reason]) and
 * when ([failedAt]), the automatic retries made so far ([retries]) of the [maxRetries] its retry spec allows, and when
 * the next one is due ([nextRetryAt]). The entry itself ([entry]) and its retry spec ([spec]) are the ledger's records,
 * at the SPI's level; an operator reads the rest of the entry in the ops console.
 *
 * Fields may be added: Wow builds a notice ([of], at the SPI's level); an application reads one.
 */
class ParkedNotice @InternalWowApi constructor(
    val kind: Kind,
    val group: String,
    /** The failure ledger's entry: a record of the ledger port's, so at the SPI's level. */
    @property:WowSpi
    val entry: ParkedAggregate,
    /** The retry spec the entry is retried by: a record of the ledger port's, so at the SPI's level. */
    @property:WowSpi
    val spec: ParkedRetrySpec,
    val at: Long,
) {
    /** The context of the subscription that parked the entry. */
    val context: String get() = entry.subscription.context

    /** The subscription that parked the entry, by its name in [context] (`processor.function`, or a built-in's). */
    val subscription: String get() = entry.subscription.name

    /** The parked aggregate. */
    val ref: AggregateRef get() = entry.ref

    /** The version of the aggregate's event the subscription parked at, and goes on from once the entry clears. */
    val fromVersion: Int get() = entry.fromVersion

    /**
     * Why the aggregate parked: what failed, never a value it failed on (see the failure guide), or, for an entry that
     * can only be skipped, why.
     */
    val reason: String get() = entry.reason

    /** When the aggregate parked (epoch milliseconds). */
    val failedAt: Long get() = entry.failedAt

    /** The automatic retries made so far. */
    val retries: Int get() = entry.retries

    /** How many automatic retries the entry's retry spec allows. */
    val maxRetries: Int get() = spec.maxRetries

    /** When the next automatic retry is due (epoch milliseconds), null when there is none. */
    val nextRetryAt: Long? get() = entry.nextRetryAt(spec)

    /**
     * What befell the entry.
     *
     * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
     */
    enum class Kind {
        /** The subscription parked the aggregate: its handler kept failing, or a business rejection stopped it. */
        PARKED,

        /** An automatic retry failed and was the last its spec allows: the entry waits for an operator. */
        EXHAUSTED,
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is ParkedNotice && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ParkedNotice(kind=$kind, group=$group, context=$context, entry=$entry, spec=$spec, at=$at)"

    private fun valueFields(): List<Any?> = listOf(kind, group, entry, spec, at)

    /** This ParkedNotice with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        kind: Kind = this.kind,
        group: String = this.group,
        entry: ParkedAggregate = this.entry,
        spec: ParkedRetrySpec = this.spec,
        at: Long = this.at,
    ): ParkedNotice = ParkedNotice(kind, group, entry, spec, at)

    companion object {
        /**
         * A ParkedNotice of the given fields; parameters added later come last, with a default. It takes the ledger's
         * records, so it is at the SPI's level: a test of a notifier builds one, as Wow does.
         */
        @WowSpi
        @JvmStatic
        fun of(
            kind: Kind,
            group: String,
            entry: ParkedAggregate,
            spec: ParkedRetrySpec,
            at: Long
        ): ParkedNotice = ParkedNotice(kind, group, entry, spec, at)
    }
}

/** Tells [notice] to this notifier, logging a failure instead of throwing it. */
@Suppress("TooGenericExceptionCaught") // A notification is best effort: whatever fails, the subscription goes on.
internal suspend fun ParkedNotifier.notifyQuietly(notice: ParkedNotice) {
    if (this === ParkedNotifier.NONE) {
        return
    }
    try {
        notify(notice)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        logger.warn(failure) {
            "Failed to notify that ${notice.ref} is ${notice.kind} by ${notice.context}.${notice.subscription}."
        }
    }
}

private val logger = KotlinLogging.logger {}

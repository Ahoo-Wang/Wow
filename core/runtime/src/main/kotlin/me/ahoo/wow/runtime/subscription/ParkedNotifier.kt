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
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec

/**
 * Hears what happens to a subscription's parked entries that an operator should know of (roadmap P.1, 9.x's WeCom
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
 * What a [ParkedNotifier] hears: [kind] happened at [at] (epoch milliseconds) to [entry], parked by a subscription of
 * the runtime of lease [group], whose retry spec is [spec].
 */
data class ParkedNotice(
    val kind: Kind,
    val group: String,
    val entry: ParkedAggregate,
    val spec: ParkedRetrySpec,
    val at: Long,
) {
    /** When the next automatic retry is due, null when there is none. */
    val nextRetryAt: Long? get() = entry.nextRetryAt(spec)

    enum class Kind {
        /** The subscription parked the aggregate: its handler kept failing, or a business rejection stopped it. */
        PARKED,

        /** An automatic retry failed and was the last its spec allows: the entry waits for an operator. */
        EXHAUSTED,
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
            "Failed to notify that ${notice.entry.ref} is ${notice.kind} by ${notice.entry.subscription}."
        }
    }
}

private val logger = KotlinLogging.logger {}

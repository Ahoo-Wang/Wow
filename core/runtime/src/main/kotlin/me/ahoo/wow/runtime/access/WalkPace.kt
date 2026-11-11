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

package me.ahoo.wow.runtime.access

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The pace of one type's access walk on a node: the [share] of the time it takes, from [most] (the configured one) down
 * to an eighth of it, and at most [rate] aggregates a second.
 *
 * - **A share by construction**: after an aggregate whose stamping took `t`, the walk waits `t * (1 - share) / share`
 *   ([pause]). Its stamping takes longer as the store's load rises, from whichever node, so the pace follows the store,
 *   not this node's view of it.
 * - **Giving way**: once a [PERIOD] ([sense]), the share halves when the log store's writes pressed, and else comes
 *   back by an eighth of [most]: additive increase, multiplicative decrease, never below an eighth of [most], so the
 *   walk ends in bounded time however long the writes press.
 * - **Pages**: as many aggregates as the last pace stamps in an interval ([pageOf]), one to learn the pace from.
 */
internal class WalkPace(
    private val most: Double,
    private val rate: Int,
    private val time: TimeSource = TimeSource.Monotonic,
) {
    init {
        require(most > 0.0 && most <= 1.0) { "A walk takes a share of the time above 0 and at most 1." }
        require(rate >= 1) { "A walk stamps at least one aggregate a second." }
    }

    /** The least share: an eighth of [most]. */
    val least: Double = most / STEPS

    /** The share of the time the walk takes now. */
    @Volatile
    var share: Double = most
        private set

    private var sensed: TimeMark? = null

    /** The last aggregate's stamping and pause together. */
    private var cycle: Duration? = null

    /** Halves the share when [pressed] says the writes pressed, else adds back an eighth of [most]; once a [PERIOD]. */
    fun sense(pressed: () -> Boolean) {
        if (sensed?.let { it.elapsedNow() < PERIOD } == true) return
        sensed = time.markNow()
        if (pressed()) press() else share = minOf(most, share + least)
    }

    /** Halves the share, down to [least]. */
    fun press() {
        share = maxOf(least, share / 2)
    }

    /** The wait after an aggregate whose stamping [took]: at most [share] of the time, at most [rate] a second. */
    fun pause(took: Duration): Duration {
        val shared = took * ((1 - share) / share)
        val rated = 1.seconds / rate - took
        val pause = maxOf(shared, rated, Duration.ZERO)
        cycle = took + pause
        return pause
    }

    /**
     * The aggregates of a page: as many as the last pace stamps in [interval], from 1 to [batch]; one before the first
     * aggregate, whose cost says what the pace is, and [batch] when the last one cost nothing to measure.
     */
    fun pageOf(batch: Int, interval: Duration): Int {
        val last = cycle ?: return 1
        return if (last.isPositive()) (interval / last).toInt().coerceIn(1, batch) else batch
    }

    override fun toString(): String = "WalkPace(share=$share, most=$most, rate=$rate)"

    companion object {
        /** How often the pace reads the writes' pressure: the period the stores' adaptive query bounds read it in. */
        val PERIOD: Duration = 1.seconds

        /** The steps between the least share and the most: the least is an eighth. */
        private const val STEPS = 8
    }
}

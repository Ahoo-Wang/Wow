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

package me.ahoo.wow.postgres.timer

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.timer.Timer
import org.junit.jupiter.api.Test
import tools.jackson.core.JacksonException

/** How the timer store names a stored rule it cannot read, and the order it merges a read past skipped rows in. */
class PostgresTimerRulesTest {
    /** The failure of decoding [text]: the store names the value from it. */
    private fun failureOf(text: String): JacksonException = try {
        PostgresTimerStore.json.readValue(text, ScheduleRule::class.java)
        throw AssertionError("$text decodes.")
    } catch (failure: JacksonException) {
        failure
    }

    @Test
    fun `a later version's rule type should be named by its type`() {
        val text = """{"type":"later","at":5}"""
        PostgresTimerStore.unreadable(failureOf(text), text).assert().isEqualTo("rule.type" to "later")
    }

    @Test
    fun `a misfire this version does not know should be named by its field`() {
        val text = """{"type":"cron","expression":"0 0 * * * *","timeZone":"UTC","misfire":"later"}"""
        PostgresTimerStore.unreadable(failureOf(text), text).assert().isEqualTo("rule.misfire" to "later")
    }

    @Test
    fun `any other failure should name the rule itself`() {
        val text = """{"type":"at","""
        PostgresTimerStore.unreadable(failureOf(text), text).assert().isEqualTo("rule" to text)
    }

    /** "￿" sorts before "😀" (U+1F600) by code point but after it by UTF-16 unit, as Java orders strings. */
    @Test
    fun `timers due at once should be ordered by their ids' code points, as the statement orders them`() {
        val emoji = timer("😀", dueAt = 5)
        val last = timer("￿", dueAt = 5)
        val earlier = timer("z", dueAt = 4)
        listOf(emoji, last, earlier).sortedWith(PostgresTimerStore.DUE).map { it.id }.assert()
            .containsExactly("z", "￿", "😀")
        PostgresTimerStore.codePoints("a", "ab").assert().isNegative()
        PostgresTimerStore.codePoints("ab", "ab").assert().isZero()
    }

    private fun timer(id: String, dueAt: Long): Timer {
        val ref = AggregateType("shop", "order").ref("t", id)
        return Timer(
            id = id,
            owner = ref,
            key = "k",
            version = 1,
            partition = 0,
            rule = ScheduleRule.At(dueAt),
            dueAt = dueAt,
            target = ref,
            commandType = "shop.order.Cancel",
            body = "{}",
        )
    }
}

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

package me.ahoo.wow.benchmark.transfer

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedOutcome
import org.junit.jupiter.api.Test
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(InternalWowApi::class)
class BenchOutcomesTest {
    @Test
    fun `counts each stage's outcomes, the forwards, the cache's and the refusals' codes, leaving out what was never seen`() {
        val outcomes = BenchOutcomes()
        outcomes.admitting("transfer", "account", "deposit", emptyMap()).outcome(ObservedOutcome.ADMITTED)
        outcomes.admitting("transfer", "account", "deposit", emptyMap()).outcome(ObservedOutcome.REFUSED)
        repeat(3) { outcomes.appending(mockk()).outcome(ObservedOutcome.COMMITTED) }
        outcomes.appending(mockk()).outcome(ObservedOutcome.CONFLICT)
        outcomes.executing(mockk(), "Deposit").outcome(ObservedOutcome.COMMITTED)
        outcomes.observed(ObservedEvent.StateCache("transfer", ObservedEvent.StateCache.Outcome.HIT))
        outcomes.observed(ObservedEvent.StateCache("transfer", ObservedEvent.StateCache.Outcome.MISS))
        outcomes.observed(ObservedEvent.StateCache("transfer", ObservedEvent.StateCache.Outcome.MISS))
        outcomes.observed(ObservedEvent.Refused(AggregateType("transfer", "account"), "wow.command.busy"))
        val account = AggregateType("transfer", "account")
        repeat(2) { outcomes.observed(ObservedEvent.ForwardFailed(account, ObservedEvent.ForwardFailed.Outcome.BUSY)) }
        outcomes.observed(ObservedEvent.ForwardFailed(account, ObservedEvent.ForwardFailed.Outcome.LOCAL))
        repeat(3) { outcomes.resuming(emptyMap()).assert().isEqualTo(EmptyCoroutineContext) }
        outcomes.observed(ObservedEvent.StateCache("transfer", ObservedEvent.StateCache.Outcome.STALE))

        outcomes.counts().assert().isEqualTo(
            mapOf(
                "admit.admitted" to 1L,
                "admit.refused" to 1L,
                "refused.wow.command.busy" to 1L,
                "forward.in" to 3L,
                "forward.local" to 1L,
                "forward.busy" to 2L,
                "execute.committed" to 1L,
                "append.committed" to 3L,
                "append.conflict" to 1L,
                "cache.hit" to 1L,
                "cache.miss" to 2L,
                "cache.stale" to 1L,
            ),
        )
    }
}

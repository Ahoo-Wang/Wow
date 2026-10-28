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

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ParkedRetryTest {
    private val spec = ParkedRetrySpec(maxRetries = 3, initialBackoff = 10.seconds, timeout = 30.seconds)
    private val entry = ParkedAggregate("sub", AggregateType("shop", "cart").ref("t", "x"), 1, "boom", FAILED_AT)

    @Test
    fun `the backoff doubles per retry from the minimum, saturating`() {
        spec.backoff(0).assert().isEqualTo(10.seconds)
        spec.backoff(1).assert().isEqualTo(20.seconds)
        spec.backoff(3).assert().isEqualTo(80.seconds)
        spec.backoff(-1).assert().isEqualTo(10.seconds)
        spec.backoff(Int.MAX_VALUE).assert().isEqualTo(Long.MAX_VALUE.milliseconds)
        ParkedRetrySpec(initialBackoff = Duration.ZERO).backoff(5).assert().isEqualTo(Duration.ZERO)
    }

    @Test
    fun `the defaults are 9_x's`() {
        val defaults = ParkedRetrySpec()
        defaults.maxRetries.assert().isEqualTo(10)
        defaults.initialBackoff.assert().isEqualTo(3.minutes)
        defaults.timeout.assert().isEqualTo(2.minutes)
    }

    @Test
    fun `a spec is never negative nor infinite`() {
        assertThrownBy<IllegalArgumentException> { ParkedRetrySpec(maxRetries = -1) }
        assertThrownBy<IllegalArgumentException> { ParkedRetrySpec(initialBackoff = (-1).seconds) }
        assertThrownBy<IllegalArgumentException> { ParkedRetrySpec(timeout = Duration.INFINITE) }
        assertThrownBy<IllegalArgumentException> { entry.copyWith(retries = -1) }
        assertThrownBy<IllegalArgumentException> { ParkedAmendment.ClaimRetry(-1, 0) }
    }

    @Test
    fun `the first retry is due the minimum backoff after the failure`() {
        entry.nextRetryAt(spec).assert().isEqualTo(FAILED_AT + 10_000)
        entry.retryable(spec).assert().isTrue()
        entry.exhausted(spec).assert().isFalse()
    }

    @Test
    fun `a later retry is due its backoff after the last one started, not before its timeout`() {
        val retried = entry.copyWith(retries = 1, retryAt = FAILED_AT + 10_000)
        retried.nextRetryAt(spec).assert().isEqualTo(FAILED_AT + 10_000 + 30_000)
        val later = entry.copyWith(retries = 2, retryAt = FAILED_AT + 40_000)
        later.nextRetryAt(spec).assert().isEqualTo(FAILED_AT + 40_000 + 40_000)
        entry.copyWith(retryAt = Long.MAX_VALUE - 1).nextRetryAt(spec).assert().isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `an entry spent, marked unrecoverable or gone from its source is not retried automatically`() {
        val spent = entry.copyWith(retries = 3, retryAt = FAILED_AT)
        spent.exhausted(spec).assert().isTrue()
        spent.nextRetryAt(spec).assert().isNull()
        entry.copyWith(recoverable = Recoverable.UNRECOVERABLE).nextRetryAt(spec).assert().isNull()
        entry.copyWith(recoverable = Recoverable.RECOVERABLE).nextRetryAt(spec).assert().isNotNull()
        entry.copyWith(reason = "${ParkedAggregate.UNRECOVERABLE}: gone").retryable(spec).assert().isFalse()
        entry.nextRetryAt(spec.copyWith(maxRetries = 0)).assert().isNull()
    }

    @Test
    fun `amendments apply as the ledger applies them`() {
        entry.amended(ParkedAmendment.ClaimRetry(0, 5)).assert().isEqualTo(entry.copyWith(retries = 1, retryAt = 5))
        entry.amended(ParkedAmendment.ClaimRetry(1, 5)).assert().isNull()
        entry.amended(ParkedAmendment.ApplyRetrySpec(spec))!!.retrySpec.assert().isEqualTo(spec)
        entry.amended(ParkedAmendment.MarkRecoverable(Recoverable.RECOVERABLE))!!
            .recoverable.assert().isEqualTo(Recoverable.RECOVERABLE)
        val requested = entry.amended(ParkedAmendment.Request(LedgerRequest.RESUME))!!
        requested.request.assert().isEqualTo(LedgerRequest.RESUME)
        requested.amended(ParkedAmendment.TakeRequest(LedgerRequest.SKIP)).assert().isNull()
        requested.amended(ParkedAmendment.TakeRequest(LedgerRequest.RESUME)).assert().isEqualTo(entry)
    }

    private companion object {
        const val FAILED_AT = 1_700_000_000_000L
    }
}

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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** The statements a store's transaction sends with its first one ([PostgresTimeouts.transactionBound]). */
class TransactionBoundTest {
    @Test
    fun `an asynchronous bound turns synchronous commit off beside the timeouts`() {
        val bound = PostgresTimeouts.TransactionBound(NETWORK, NO_BUDGET, readOnly = false, asynchronous = true)
        bound.alone.assert().isEqualTo(
            "SET LOCAL synchronous_commit = off; SET LOCAL statement_timeout = 2000; SET LOCAL lock_timeout = 2000; " +
                "SET LOCAL idle_in_transaction_session_timeout = 6000",
        )
        bound.count.assert().isEqualTo(4)
        // On a connection without a network timeout, the flag alone.
        PostgresTimeouts.TransactionBound(0, NO_BUDGET, readOnly = false, asynchronous = true).alone
            .assert().isEqualTo("SET LOCAL synchronous_commit = off")
        PostgresTimeouts.TransactionBound(0, NO_BUDGET, readOnly = false).count.assert().isZero()
    }

    @Test
    fun `a bound is for its own network timeout, budget and flags only`() {
        val bound = PostgresTimeouts.TransactionBound(NETWORK, NO_BUDGET, readOnly = false, asynchronous = true)
        bound.isFor(NETWORK, NO_BUDGET, readOnly = false, asynchronous = true).assert().isTrue()
        bound.isFor(NETWORK + 1, NO_BUDGET, readOnly = false, asynchronous = true).assert().isFalse()
        bound.isFor(NETWORK, 500, readOnly = false, asynchronous = true).assert().isFalse()
        bound.isFor(NETWORK, NO_BUDGET, readOnly = true, asynchronous = true).assert().isFalse()
        bound.isFor(NETWORK, NO_BUDGET, readOnly = false, asynchronous = false).assert().isFalse()
    }

    private companion object {
        const val NETWORK = 3_000

        /** No statement budget of its own, as PostgresTimeouts passes it. */
        const val NO_BUDGET = -1L
    }
}

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

package me.ahoo.wow.benchmark.axon

import org.axonframework.test.aggregate.AggregateTestFixture
import org.junit.jupiter.api.Test

/** The account's rules, the cases of the Wow benchmark's `TransferCases` that E1 and E3 reach. */
class AccountTest {
    private val fixture = AggregateTestFixture(Account::class.java)

    @Test
    fun `an account opens with its balance`() {
        fixture.givenNoPriorActivity()
            .`when`(OpenAccount("a", "owner", 10))
            .expectEvents(AccountOpened("a", "owner", 10))
            .expectResultMessagePayload(0L)
    }

    @Test
    fun `a deposit of a positive amount is stored, any other is refused`() {
        fixture.given(AccountOpened("a", "owner", 10))
            .`when`(Deposit("a", 5))
            .expectEvents(Deposited(5))
            .expectResultMessagePayload(1L)
        fixture.given(AccountOpened("a", "owner", 10))
            .`when`(Deposit("a", 0))
            .expectException(AccountRejected::class.java)
    }

    @Test
    fun `a transfer out locks an amount the balance covers`() {
        fixture.given(AccountOpened("a", "owner", 10))
            .`when`(Prepare("a", "b", 4, "t-1"))
            .expectEvents(AmountLocked(4), Prepared("a", "b", 4, "t-1"))
        fixture.given(AccountOpened("a", "owner", 10))
            .`when`(Prepare("a", "b", 11, "t-1"))
            .expectException(AccountRejected::class.java)
    }

    @Test
    fun `the entry and the confirmation complete a transfer`() {
        fixture.given(AccountOpened("b", "owner", 0))
            .`when`(Entry("b", "a", 4, "t-1"))
            .expectEvents(AmountEntered("a", 4, "t-1"))
        // A fixture keeps the aggregate of its first given: the source's account takes another.
        AggregateTestFixture(Account::class.java)
            .given(AccountOpened("a", "owner", 10), AmountLocked(4), Prepared("a", "b", 4, "t-1"))
            .`when`(Confirm("a", 4))
            .expectEvents(Confirmed(4))
    }
}

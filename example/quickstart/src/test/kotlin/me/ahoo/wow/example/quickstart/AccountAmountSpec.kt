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

package me.ahoo.wow.example.quickstart

import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test

/** The amount checks the quickstart page does not show; kept apart from [AccountSpec], which the page quotes. */
class AccountAmountSpec {
    private val model = accountModel("bank")

    @Test
    fun `a deposit that is not positive is rejected`() = aggregateSpec(model, id = "alice") {
        given(AccountOpened("Alice"))
        whenCommand(Deposit(0)) { expectRejected(Account.Rejections.INVALID_AMOUNT) }
    }

    @Test
    fun `a withdrawal that is not positive is rejected`() = aggregateSpec(model, id = "alice") {
        given(AccountOpened("Alice"), Deposited(100))
        whenCommand(Withdraw(-5)) { expectRejected(Account.Rejections.INVALID_AMOUNT) }
    }
}

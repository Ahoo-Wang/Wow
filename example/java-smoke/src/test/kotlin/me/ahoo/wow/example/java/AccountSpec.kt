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

package me.ahoo.wow.example.java

import me.ahoo.test.asserts.assert
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test

/** The Java aggregate through the model the compiler generated from its Java sources; the test DSL is Kotlin. */
class AccountSpec {
    private val model = accountModel("smoke")

    @Test
    fun `opening an account should name its owner`() = aggregateSpec(model, id = "alice") {
        whenCommand(OpenAccount("Alice")) {
            expectEvents(AccountOpened("Alice"))
            expectState { owner.assert().isEqualTo("Alice") }
        }
    }

    @Test
    fun `a Java decision should produce its events`() = aggregateSpec(model, id = "alice") {
        given(AccountOpened("Alice"))
        whenCommand(Deposit(100)) {
            expectEvents(Deposited(100))
            expectState { balance.assert().isEqualTo(100) }
        }
    }

    // region rejection
    @Test
    fun `a Java rejection should carry the code of a Java field`() = aggregateSpec(model, id = "alice") {
        given(AccountOpened("Alice"), Deposited(100))
        whenCommand(Withdraw(150)) { expectRejected(AccountRejections.INSUFFICIENT_BALANCE) }
        whenCommand(Deposit(0)) { expectRejected(AccountRejections.INVALID_AMOUNT) }
        whenCommand(Withdraw(30)) {
            expectEvents(Withdrawn(30))
            expectState { balance.assert().isEqualTo(70) }
        }
    }
    // endregion
}

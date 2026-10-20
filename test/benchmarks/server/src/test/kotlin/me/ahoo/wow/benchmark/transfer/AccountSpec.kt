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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** The account on V10's test DSL, over the shared table of cases. */
class AccountSpec {
    private val model = accountModel(TransferContext.NAME)

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `the account should decide as the shared table says`(case: AccountCase) =
        aggregateSpec(model, id = TransferCases.ACCOUNT_ID) {
            if (case.given.isNotEmpty()) given(*case.given.toTypedArray())
            whenCommand(case.command) {
                if (case.rejection == null) {
                    expectEvents(*case.events.toTypedArray())
                } else {
                    expectRejected(case.rejection)
                }
                case.state?.let { expected ->
                    expectState { Balances(owner, balance, locked, frozen).assert().isEqualTo(expected) }
                }
            }
        }

    companion object {
        @JvmStatic
        fun cases(): List<AccountCase> = TransferCases.ACCOUNT
    }
}

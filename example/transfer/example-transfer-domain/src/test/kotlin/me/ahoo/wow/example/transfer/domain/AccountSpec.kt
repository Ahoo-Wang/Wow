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

package me.ahoo.wow.example.transfer.domain

import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.transfer.api.AccountCreated
import me.ahoo.wow.example.transfer.api.AccountFrozen
import me.ahoo.wow.example.transfer.api.AmountEntered
import me.ahoo.wow.example.transfer.api.AmountLocked
import me.ahoo.wow.example.transfer.api.CreateAccount
import me.ahoo.wow.example.transfer.api.Entry
import me.ahoo.wow.example.transfer.api.EntryFailed
import me.ahoo.wow.example.transfer.api.Prepare
import me.ahoo.wow.example.transfer.api.Prepared
import me.ahoo.wow.test.AggregateSpec
import org.assertj.core.api.Assertions.*

class AccountSpec : AggregateSpec<Account, AccountState>({

    on {
        val createAccount = CreateAccount("name", 100)
        whenCommand(createAccount) {
            expectEventType(AccountCreated::class)
            expectState {
                name.assert().isEqualTo(createAccount.name)
                balanceAmount.assert().isEqualTo(createAccount.balance)
            }
            fork {
                val prepare = Prepare("to", 100)
                whenCommand(prepare) {
                    expectEventType(AmountLocked::class, Prepared::class)
                    expectState {
                        balanceAmount.assert().isEqualTo(createAccount.balance - prepare.amount)
                    }
                }
            }
            fork {
                val stateRoot = stateRoot
                givenEvent(AccountFrozen("")) {
                    whenCommand(Prepare("to", 100)) {
                        expectError<IllegalStateException> {
                            assertThat(this).hasMessage("账号已冻结无法转账.")
                        }
                        expectState {
                            name.assert().isEqualTo(createAccount.name)
                            balanceAmount.assert().isEqualTo(createAccount.balance)
                            isFrozen.assert().isTrue()
                        }
                    }
                    val entry = Entry(stateRoot.id, "sourceId", 100)
                    whenCommand(entry) {
                        expectEventType(EntryFailed::class)
                        expectState {
                            balanceAmount.assert().isEqualTo(100)
                            isFrozen.assert().isTrue()
                        }
                    }
                }
            }
            fork {
                val prepare = Prepare("to", createAccount.balance + 1)
                whenCommand(prepare) {
                    expectError<IllegalStateException> {
                        this.assert().hasMessage("账号余额不足.")
                    }
                    expectState {
                        name.assert().isEqualTo(createAccount.name)
                        balanceAmount.assert().isEqualTo(createAccount.balance)
                    }
                }
            }
            fork {
                val entry = Entry(stateRoot.id, "sourceId", 100)
                whenCommand(entry) {
                    expectEventType(AmountEntered::class)
                    expectState {
                        balanceAmount.assert().isEqualTo(200)
                    }
                }
            }
        }
    }
})

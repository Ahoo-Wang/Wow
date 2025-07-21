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
import me.ahoo.wow.example.transfer.api.AccountUnfrozen
import me.ahoo.wow.example.transfer.api.AmountEntered
import me.ahoo.wow.example.transfer.api.AmountLocked
import me.ahoo.wow.example.transfer.api.AmountUnlocked
import me.ahoo.wow.example.transfer.api.Confirm
import me.ahoo.wow.example.transfer.api.Confirmed
import me.ahoo.wow.example.transfer.api.CreateAccount
import me.ahoo.wow.example.transfer.api.Entry
import me.ahoo.wow.example.transfer.api.EntryFailed
import me.ahoo.wow.example.transfer.api.FreezeAccount
import me.ahoo.wow.example.transfer.api.LockAmount
import me.ahoo.wow.example.transfer.api.Prepare
import me.ahoo.wow.example.transfer.api.Prepared
import me.ahoo.wow.example.transfer.api.UnfreezeAccount
import me.ahoo.wow.example.transfer.api.UnlockAmount
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.test.aggregateVerifier
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test

internal class AccountKTest {
    @Test
    fun createAccount() {
        aggregateVerifier<Account, AccountState>()
            .given()
            .whenCommand(CreateAccount("name", 100))
            .expectEventType(AccountCreated::class)
            .expectState {
                name.assert().isEqualTo("name")
                balanceAmount.assert().isEqualTo(100)
            }
            .verify()
    }

    @Test
    fun prepare() {
        aggregateVerifier<Account, AccountState>()
            .given(AccountCreated("name", 100))
            .whenCommand(Prepare("name", 100))
            .expectEventType(AmountLocked::class, Prepared::class)
            .expectState {
                name.assert().isEqualTo("name")
                balanceAmount.assert().isEqualTo(0)
            }
            .verify()
    }

    @Test
    fun prepareGivenFrozen() {
        aggregateVerifier<Account, AccountState>()
            .given(AccountCreated("name", 100), AccountFrozen(""))
            .whenCommand(Prepare("name", 100))
            .expectError<IllegalStateException> {
                assertThat(this).hasMessage("账号已冻结无法转账.")
            }
            .expectState {
                name.assert().isEqualTo("name")
                balanceAmount.assert().isEqualTo(100)
                isFrozen.assert().isTrue()
            }
            .verify()
    }

    @Test
    fun prepareGivenBalanceInsufficient() {
        aggregateVerifier<Account, AccountState>()
            .given(AccountCreated("name", 100))
            .whenCommand(Prepare("name", 200))
            .expectError<IllegalStateException> {
                assertThat(this).hasMessage("账号余额不足.")
            }
            .expectState {
                name.assert().isEqualTo("name")
                balanceAmount.assert().isEqualTo(100)
            }
            .verify()
    }

    @Test
    fun entry() {
        val aggregateId = GlobalIdGenerator.generateAsString()
        aggregateVerifier<Account, AccountState>(aggregateId)
            .given(AccountCreated("name", 100))
            .whenCommand(Entry(aggregateId, "sourceId", 100))
            .expectEventType(AmountEntered::class)
            .expectState {
                name.assert().isEqualTo("name")
                balanceAmount.assert().isEqualTo(200)
            }
            .verify()
    }

    @Test
    fun entryGivenFrozen() {
        val aggregateId = GlobalIdGenerator.generateAsString()
        aggregateVerifier<Account, AccountState>(aggregateId)
            .given(AccountCreated("name", 100), AccountFrozen(""))
            .whenCommand(Entry(aggregateId, "sourceId", 100))
            .expectEventType(EntryFailed::class)
            .expectState {
                name.assert().isEqualTo("name")
                balanceAmount.assert().isEqualTo(100)
                isFrozen.assert().isTrue()
            }
            .verify()
    }

    @Test
    fun confirm() {
        val aggregateId = GlobalIdGenerator.generateAsString()
        aggregateVerifier<Account, AccountState>(aggregateId)
            .given(AccountCreated("name", 100), AmountLocked(100))
            .whenCommand(Confirm(aggregateId, 100))
            .expectEventType(Confirmed::class)
            .expectState {
                name.assert().isEqualTo("name")
                balanceAmount.assert().isEqualTo(0)
                lockedAmount.assert().isEqualTo(0)
                isFrozen.assert().isFalse()
            }
            .verify()
    }

    @Test
    fun unlockAmount() {
        val aggregateId = GlobalIdGenerator.generateAsString()
        aggregateVerifier<Account, AccountState>(aggregateId)
            .given(AccountCreated("name", 100), AmountLocked(100))
            .whenCommand(UnlockAmount(aggregateId, 100))
            .expectEventType(AmountUnlocked::class)
            .expectState {
                name.assert().isEqualTo("name")
                balanceAmount.assert().isEqualTo(100)
                lockedAmount.assert().isEqualTo(0)
                isFrozen.assert().isFalse()
            }
            .verify()
    }

    @Test
    fun freezeAccount() {
        val aggregateId = GlobalIdGenerator.generateAsString()
        aggregateVerifier<Account, AccountState>(aggregateId)
            .given(AccountCreated("name", 100))
            .whenCommand(FreezeAccount(""))
            .expectEventType(AccountFrozen::class)
            .expectState {
                name.assert().isEqualTo("name")
                balanceAmount.assert().isEqualTo(100)
                lockedAmount.assert().isEqualTo(0)
                isFrozen.assert().isTrue()
            }
            .verify()
    }

    @Test
    fun freezeAccountGivenFrozen() {
        val aggregateId = GlobalIdGenerator.generateAsString()
        aggregateVerifier<Account, AccountState>(aggregateId)
            .given(AccountCreated("name", 100), AccountFrozen(""))
            .whenCommand(FreezeAccount(""))
            .expectError<IllegalStateException> {
                assertThat(this).hasMessage("账号已冻结无需再次冻结.")
            }
            .expectState {
                name.assert().isEqualTo("name")
                balanceAmount.assert().isEqualTo(100)
                lockedAmount.assert().isEqualTo(0)
                isFrozen.assert().isTrue()
            }
            .verify()
    }

    @Test
    fun unfreezeAccount() {
        val aggregateId = GlobalIdGenerator.generateAsString()
        aggregateVerifier<Account, AccountState>(aggregateId)
            .given(AccountCreated("name", 100), AccountFrozen(""))
            .whenCommand(UnfreezeAccount(""))
            .expectState {
                name.assert().isEqualTo("name")
                balanceAmount.assert().isEqualTo(100)
                lockedAmount.assert().isEqualTo(0)
                isFrozen.assert().isFalse()
            }
            .verify()
    }

    @Test
    fun unfreezeAccountGivenUnfrozen() {
        val aggregateId = GlobalIdGenerator.generateAsString()
        aggregateVerifier<Account, AccountState>(aggregateId)
            .given(AccountCreated("name", 100), AccountUnfrozen(""))
            .whenCommand(UnfreezeAccount(""))
            .expectState {
                name.assert().isEqualTo("name")
                balanceAmount.assert().isEqualTo(100)
                lockedAmount.assert().isEqualTo(0)
                isFrozen.assert().isFalse()
            }
            .verify()
    }

    @Test
    fun lockAmount() {
        val aggregateId = GlobalIdGenerator.generateAsString()
        aggregateVerifier<Account, AccountState>(aggregateId)
            .given(AccountCreated("name", 100L))
            .whenCommand(LockAmount(10))
            .expectEventType(AmountLocked::class)
            .expectState {
                name.assert().isEqualTo("name")
                balanceAmount.assert().isEqualTo(90)
                lockedAmount.assert().isEqualTo(10)
                isFrozen.assert().isFalse()
            }
            .verify()
    }
}

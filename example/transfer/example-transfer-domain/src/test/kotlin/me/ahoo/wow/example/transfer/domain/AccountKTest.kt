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

import me.ahoo.wow.example.transfer.api.AccountCreated
import me.ahoo.wow.example.transfer.api.AccountFrozen
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
import me.ahoo.wow.example.transfer.api.UnlockAmount
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.test.aggregateVerifier
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

internal class AccountKTest {
    @Test
    fun createAccount() {
        aggregateVerifier<Account, AccountState>()
            .given()
            .`when`(CreateAccount("name", 100))
            .expectEventType(AccountCreated::class.java)
            .expectState {
                assertThat(it.name, equalTo("name"))
                assertThat(it.balanceAmount, equalTo(100))
            }
            .verify()
    }

    @Test
    fun prepare() {
        aggregateVerifier<Account, AccountState>()
            .given(AccountCreated("name", 100))
            .`when`(Prepare("name", 100))
            .expectEventType(AmountLocked::class.java, Prepared::class.java)
            .expectState {
                assertThat(it.name, equalTo("name"))
                assertThat(it.balanceAmount, equalTo(0))
            }
            .verify()
    }

    @Test
    fun entry() {
        val aggregateId = GlobalIdGenerator.generateAsString()
        aggregateVerifier<Account, AccountState>(aggregateId)
            .given(AccountCreated("name", 100))
            .`when`(Entry(aggregateId, "sourceId", 100))
            .expectEventType(AmountEntered::class.java)
            .expectState {
                assertThat(it.name, equalTo("name"))
                assertThat(it.balanceAmount, equalTo(200))
            }
            .verify()
    }

    @Test
    fun entryGivenFrozen() {
        val aggregateId = GlobalIdGenerator.generateAsString()
        aggregateVerifier<Account, AccountState>(aggregateId)
            .given(AccountCreated("name", 100), AccountFrozen(""))
            .`when`(Entry(aggregateId, "sourceId", 100))
            .expectEventType(EntryFailed::class.java)
            .expectState {
                assertThat(it.name, equalTo("name"))
                assertThat(it.balanceAmount, equalTo(100))
                assertThat(it.isFrozen, equalTo(true))
            }
            .verify()
    }

    @Test
    fun confirm() {
        val aggregateId = GlobalIdGenerator.generateAsString()
        aggregateVerifier<Account, AccountState>(aggregateId)
            .given(AccountCreated("name", 100), AmountLocked(100))
            .`when`(Confirm(aggregateId, 100))
            .expectEventType(Confirmed::class.java)
            .expectState {
                assertThat(it.name, equalTo("name"))
                assertThat(it.balanceAmount, equalTo(0))
                assertThat(it.lockedAmount, equalTo(0))
                assertThat(it.isFrozen, equalTo(false))
            }
            .verify()
    }

    @Test
    fun unlockAmount() {
        val aggregateId = GlobalIdGenerator.generateAsString()
        aggregateVerifier<Account, AccountState>(aggregateId)
            .given(AccountCreated("name", 100), AmountLocked(100))
            .`when`(UnlockAmount(aggregateId, 100))
            .expectEventType(AmountUnlocked::class.java)
            .expectState {
                assertThat(it.name, equalTo("name"))
                assertThat(it.balanceAmount, equalTo(100))
                assertThat(it.lockedAmount, equalTo(0))
                assertThat(it.isFrozen, equalTo(false))
            }
            .verify()
    }

    @Test
    fun freezeAccount() {
        val aggregateId = GlobalIdGenerator.generateAsString()
        aggregateVerifier<Account, AccountState>(aggregateId)
            .given(AccountCreated("name", 100))
            .`when`(FreezeAccount(""))
            .expectEventType(AccountFrozen::class.java)
            .expectState {
                assertThat(it.name, equalTo("name"))
                assertThat(it.balanceAmount, equalTo(100))
                assertThat(it.lockedAmount, equalTo(0))
                assertThat(it.isFrozen, equalTo(true))
            }
            .verify()
    }

    @Test
    fun lockAmount() {
        val aggregateId = GlobalIdGenerator.generateAsString()
        aggregateVerifier<Account, AccountState>(aggregateId)
            .given(AccountCreated("name", 100L))
            .`when`(LockAmount(10))
            .expectEventType(AmountLocked::class.java)
            .expectState {
                assertThat(it.name, equalTo("name"))
                assertThat(it.balanceAmount, equalTo(90L))
                assertThat(it.lockedAmount, equalTo(10L))
                assertThat(it.isFrozen, equalTo(false))
            }
            .verify()
    }
}

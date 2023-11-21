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
import me.ahoo.wow.example.transfer.api.CreateAccount
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
}
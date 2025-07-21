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

package me.ahoo.wow.example.transfer.domain;

import me.ahoo.wow.example.transfer.api.AccountCreated;
import me.ahoo.wow.example.transfer.api.CreateAccount;
import org.junit.jupiter.api.Test;

import static me.ahoo.wow.test.AggregateVerifier.*;
import static org.assertj.core.api.Assertions.*;

class AccountTest {

    @Test
    void createAccount() {
        aggregateVerifier(Account.class, AccountState.class)
                .given()
                .when(new CreateAccount("name", 100L))
                .expectEventType(AccountCreated.class)
                .expectEventIterator(eventIterator -> {
                    assertThat(eventIterator.hasNext()).isTrue();
                    var eventBody = eventIterator.nextEventBody(AccountCreated.class);
                    assertThat(eventBody.name()).isEqualTo("name");
                    assertThat(eventBody.balance()).isEqualTo(100L);
                    assertThat(eventIterator.hasNext()).isFalse();
                    return null;
                })
                .expectState(account -> {
                    assertThat(account.getName()).isEqualTo("name");
                    assertThat(account.getBalanceAmount()).isEqualTo(100L);
                    return null;
                })
                .verify();
    }

}
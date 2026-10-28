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

package me.ahoo.wow.example.java;

/**
 * The account's state: mutable, evolved by its public {@code onSourcing} methods, one per event. Its getters are what
 * Jackson serializes (snapshots, the test DSL's purity check).
 */
public class AccountState {
    private String owner = "";
    private long balance;

    public String getOwner() {
        return owner;
    }

    public long getBalance() {
        return balance;
    }

    public void onSourcing(AccountOpened event) {
        owner = event.owner();
    }

    public void onSourcing(Deposited event) {
        balance += event.amount();
    }

    public void onSourcing(Withdrawn event) {
        balance -= event.amount();
    }
}

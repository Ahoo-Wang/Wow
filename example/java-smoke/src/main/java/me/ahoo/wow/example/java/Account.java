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

import me.ahoo.wow.api.annotation.AggregateRoot;
import me.ahoo.wow.api.annotation.OnCommand;
import me.ahoo.wow.api.decide.Decision;

/**
 * An aggregate root written in Java. Its decide functions are plain Java methods: they return an
 * event, or a {@link Decision} built with the statics {@code Decision.events} and {@code Decision.reject}, whose events
 * {@code @OnCommand(produces)} names. A Java decide function cannot call the {@code suspend} functions of the API,
 * such as {@code DecideScope.reserve}, and the test DSL is Kotlin, so the tests of a Java domain are Kotlin.
 */
@AggregateRoot
public class Account {
    private final AccountState state;

    public Account(AccountState state) {
        this.state = state;
    }

    public AccountOpened onCommand(OpenAccount command) {
        return new AccountOpened(command.owner());
    }

    @OnCommand(produces = Deposited.class)
    public Decision onCommand(Deposit command) {
        if (command.amount() <= 0) {
            return Decision.reject(AccountRejections.INVALID_AMOUNT, command.amount() + " is not positive.");
        }
        return Decision.events(new Deposited(command.amount()));
    }

    @OnCommand(produces = Withdrawn.class)
    public Decision onCommand(Withdraw command) {
        if (command.amount() <= 0) {
            return Decision.reject(AccountRejections.INVALID_AMOUNT, command.amount() + " is not positive.");
        }
        if (command.amount() > state.getBalance()) {
            return Decision.reject(AccountRejections.INSUFFICIENT_BALANCE, "The balance is " + state.getBalance() + ".");
        }
        return Decision.events(new Withdrawn(command.amount()));
    }
}

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

import me.ahoo.wow.api.annotation.AggregateRoot;
import me.ahoo.wow.api.annotation.OnCommand;
import me.ahoo.wow.api.annotation.StaticTenantId;
import me.ahoo.wow.example.transfer.api.*;

import java.util.List;

@StaticTenantId
@AggregateRoot
public class Account {
    private final AccountState state;

    public Account(AccountState state) {
        this.state = state;
    }

    AccountCreated onCommand(CreateAccount createAccount) {
        return new AccountCreated(createAccount.name(), createAccount.balance());
    }

    @OnCommand(returns = {AmountLocked.class, Prepared.class})
    List<?> onCommand(Prepare prepare) {
        checkBalance(prepare.amount());
        return List.of(new AmountLocked(prepare.amount()), new Prepared(prepare.to(), prepare.amount()));
    }

    private void checkBalance(long amount) {
        if (state.isFrozen()) {
            throw new IllegalStateException("账号已冻结无法转账.");
        }
        if (state.getBalanceAmount() < amount) {
            throw new IllegalStateException("账号余额不足.");
        }
    }

    Object onCommand(Entry entry) {
        if (state.isFrozen()) {
            return new EntryFailed(entry.sourceId(), entry.amount());
        }
        return new AmountEntered(entry.sourceId(), entry.amount());
    }

    Confirmed onCommand(Confirm confirm) {
        return new Confirmed(confirm.amount());
    }

    AmountLocked onCommand(LockAmount lockAmount) {
        return new AmountLocked(lockAmount.amount());
    }

    AmountUnlocked onCommand(UnlockAmount unlockAmount) {
        return new AmountUnlocked(unlockAmount.amount());
    }

    AccountFrozen onCommand(FreezeAccount freezeAccount) {
        if (state.isFrozen()) {
            throw new IllegalStateException("账号已冻结无需再次冻结.");
        }
        return new AccountFrozen(freezeAccount.reason());
    }

    AccountUnfrozen onCommand(UnfreezeAccount unfreezeAccount) {
        if (!state.isFrozen()) {
            throw new IllegalStateException("账号未冻结无需解冻.");
        }
        return new AccountUnfrozen(unfreezeAccount.reason());
    }
}

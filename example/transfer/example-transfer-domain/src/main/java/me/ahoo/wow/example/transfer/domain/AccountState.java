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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import me.ahoo.wow.api.Identifier;
import me.ahoo.wow.example.transfer.api.*;
import org.jetbrains.annotations.NotNull;

public class AccountState implements Identifier {
    private final String id;
    private String name;
    /**
     * 余额
     */
    private long balanceAmount = 0L;
    /**
     * 已锁定金额
     */
    private long lockedAmount = 0L;
    /**
     * 账号已冻结标记
     */
    private boolean frozen = false;

    @JsonCreator
    public AccountState(@JsonProperty("id") String id) {
        this.id = id;
    }

    @NotNull
    @Override
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getBalanceAmount() {
        return balanceAmount;
    }

    public long getLockedAmount() {
        return lockedAmount;
    }

    public boolean isFrozen() {
        return frozen;
    }

    void onSourcing(AccountCreated accountCreated) {
        this.name = accountCreated.name();
        this.balanceAmount = accountCreated.balance();
    }

    void onSourcing(AmountLocked amountLocked) {
        balanceAmount = balanceAmount - amountLocked.amount();
        lockedAmount = lockedAmount + amountLocked.amount();
    }

    void onSourcing(AmountEntered amountEntered) {
        balanceAmount = balanceAmount + amountEntered.amount();
    }

    void onSourcing(Confirmed confirmed) {
        lockedAmount = lockedAmount - confirmed.amount();
    }

    void onSourcing(AmountUnlocked amountUnlocked) {
        lockedAmount = lockedAmount - amountUnlocked.amount();
        balanceAmount = balanceAmount + amountUnlocked.amount();
    }

    void onSourcing(AccountFrozen accountFrozen) {
        this.frozen = true;
    }

    void onSourcing(AccountUnfrozen accountUnfrozen) {
        this.frozen = false;
    }

}

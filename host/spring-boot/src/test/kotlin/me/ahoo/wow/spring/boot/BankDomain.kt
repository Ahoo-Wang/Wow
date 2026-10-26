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

package me.ahoo.wow.spring.boot

import jakarta.validation.constraints.Positive
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.aggregate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

const val BANK = "bank"
const val FROZEN = "bank.account.frozen"

/** A deposit of this amount is rejected as [FROZEN]. */
const val FROZEN_AMOUNT = 13L

/** The tag key an account derives from its balance: `gold` from [GOLD_BALANCE], else `standard`. */
const val TIER = "tier"
const val GOLD_BALANCE = 100L

data class OpenAccount(val owner: String = "")

data class Deposit(@field:Positive val amount: Long)

data class AccountOpened(val owner: String)

data class Deposited(val amount: Long)

class Account {
    var owner: String = ""
    var balance: Long = 0
}

/**
 * The aggregate ids whose commands a node decided, and the commands' wait timeouts and headers: a bean of each
 * application context, a model's dependency.
 */
class DecidedCommands {
    val ids: MutableList<String> = CopyOnWriteArrayList()
    val timeouts: MutableList<Duration> = CopyOnWriteArrayList()
    val headers: MutableList<Map<String, String>> = CopyOnWriteArrayList()
}

/**
 * The binding index of the test module, written as the compiler generates it (decision D107), with the fragment
 * `META-INF/wow/manifest/host-test-bank.json`. Its model takes the [DecidedCommands] bean.
 */
class BankModels : ModelProvider {
    override val context: String = BANK
    override val binding: Int = ModelProvider.BINDING
    override val fragment: String = "META-INF/wow/manifest/host-test-bank.json"

    override fun models(dependencies: Dependencies): List<AggregateModel<*>> {
        val decided = dependencies.get(DecidedCommands::class.java)
        return listOf(
            aggregate<Account>(BANK, "account") {
                initial { Account() }
                lifecycle()
                decide<OpenAccount>(Creation.REQUIRED) { _, body ->
                    decided.ids += command.ref.id
                    decided.timeouts += command.wait.timeout
                    decided.headers += command.headers
                    Decision.events(AccountOpened(body.owner))
                }
                decide<Deposit> { _, body ->
                    decided.ids += command.ref.id
                    if (body.amount == FROZEN_AMOUNT) {
                        Decision.reject(FROZEN, "The account takes no deposit.")
                    } else {
                        Decision.events(Deposited(body.amount))
                    }
                }
                evolve<AccountOpened> { owner = it.owner }
                evolve<Deposited> { balance += it.amount }
                // The account's tier follows its balance (decision D220): a query policy by tags follows it too.
                tags { mapOf(TIER to listOf(if (it.balance >= GOLD_BALANCE) "gold" else "standard")) }
            },
        )
    }
}

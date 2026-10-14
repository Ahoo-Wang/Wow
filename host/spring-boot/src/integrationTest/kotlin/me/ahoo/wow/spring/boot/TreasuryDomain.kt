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

import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

const val TREASURY = "treasury"
const val SEAL = "seal"
const val LEDGERBOOK = "Ledgerbook.onEvent"

/** Opens a vault that seals itself [sealAfterMillis] later. */
data class OpenVault(val sealAfterMillis: Long = 0)

/** Opens a ledger that closes itself [closeAfterMillis] later. */
data class OpenLedger(val closeAfterMillis: Long = 0)

data class Seal(val reason: String = "due")

data class VaultOpened(val sealAfterMillis: Long)

data class LedgerOpened(val closeAfterMillis: Long)

data class Sealed(val reason: String)

class Holding {
    var sealed: Boolean = false
}

/**
 * The treasury context of the mixed-store tests (decision D177): its vaults and its ledgers, each schedules its own
 * [Seal] when opened. The fragment `META-INF/wow/manifest/host-it-treasury.json` is its Manifest; the provider is not
 * registered on the class path, so only the hosts that give [TreasuryApp]'s models serve it.
 */
class TreasuryModels : ModelProvider {
    override val context: String = TREASURY
    override val fragment: String = "META-INF/wow/manifest/host-it-treasury.json"

    override fun models(dependencies: Dependencies): List<AggregateModel<*>> = listOf(
        holding<OpenVault, VaultOpened>("vault", { it.sealAfterMillis }) { VaultOpened(it.sealAfterMillis) },
        holding<OpenLedger, LedgerOpened>("ledger", { it.closeAfterMillis }) { LedgerOpened(it.closeAfterMillis) },
    )

    private inline fun <reified C : Any, reified E : Any> holding(
        name: String,
        crossinline delay: (C) -> Long,
        crossinline opened: (C) -> E,
    ): AggregateModel<Holding> = aggregate<Holding>(TREASURY, name) {
        initial { Holding() }
        lifecycle()
        decide<C>(Creation.REQUIRED) { _, body ->
            schedule(SEAL, ScheduleRule.after(delay(body).milliseconds), Seal())
            Decision.events(opened(body))
        }
        decide<Seal> { state, body -> if (state.sealed) Decision.NoOp else Decision.events(Sealed(body.reason)) }
        evolve<E> { }
        evolve<Sealed> { sealed = true }
    }
}

/** What the [LEDGERBOOK] projection of a host handled: `aggregate:id@version`. */
class Ledgerbook {
    val entries: MutableList<String> = CopyOnWriteArrayList()
}

/** The application of the mixed-store tests: the treasury's models alone, and a projection of every treasury entry. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
class TreasuryApp {
    @Bean
    fun wowModels(dependencies: Dependencies): WowModels {
        val providers = listOf<ModelProvider>(TreasuryModels())
        return WowModels(providers, ServiceNode.models(dependencies, providers))
    }

    @Bean
    fun ledgerbook(): Ledgerbook = Ledgerbook()

    @Bean
    fun ledgerbookProjection(ledgerbook: Ledgerbook): SubscriptionFactory = SubscriptionFactory { context ->
        listOf(
            Subscription(LEDGERBOOK, SubscriptionFilter(context.context)) { entry ->
                val ref = entry.stream.ref
                ledgerbook.entries += "${ref.type.name}:${ref.id}@${entry.stream.version}"
            },
        )
    }
}

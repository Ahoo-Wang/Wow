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

package me.ahoo.wow.runtime.timer

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.admission.CommittedRequests
import me.ahoo.wow.runtime.admission.CreationIds
import me.ahoo.wow.runtime.admission.ServiceCommandAdmission
import me.ahoo.wow.runtime.binding.AddItems
import me.ahoo.wow.runtime.binding.CART
import me.ahoo.wow.runtime.binding.ItemsAdded
import me.ahoo.wow.runtime.binding.SHOP
import me.ahoo.wow.runtime.binding.SHOP_SERVICE
import me.ahoo.wow.runtime.binding.TENANT
import me.ahoo.wow.runtime.binding.cartModel
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.tenant.TenantPolicy
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerOutcomes
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test

/**
 * A timer's command to an aggregate of its own context is admitted as the context's other commands that no caller sent
 * (#6556): in its target's fixed tenant unless it names another, which refuses it; a creation that names no aggregate
 * takes an id, the same for every firing of one slot, so a slot fired twice creates one aggregate; one to an aggregate
 * whose id is its owner's that names none and has no owner is refused.
 */
class TimerAdmissionTest {
    private val log = InMemoryEventLog()
    private val wallet = aggregate<Any>(SHOP, "wallet") {
        id(AggregateIdSource.OWNER)
        initial { Any() }
        decide<AddItems>(Creation.ALLOWED) { _, _ -> Decision.events(ItemsAdded(listOf())) }
        evolve<ItemsAdded> { }
    }
    private val registry = WaitRegistry()
    private val coordinator = WaitCoordinator(
        Executor(Kernel(log)),
        registry,
        SubscriptionDirectory.NONE,
        LocalSignalSender("node-1", registry),
        Origin("node-1", "node-1:9090", SHOP_SERVICE),
        WaitOptions(),
    )
    private val admission = ServiceCommandAdmission(
        emptyList(),
        CreationIds(IdGenerators.DEFAULT, CommittedRequests.of(log)),
        TenantPolicy.fixed(mapOf(CART to SHARED)),
    )
    private val firer = ContextTimerFirer(listOf(cartModel(), wallet), coordinator, admission)

    /** The timer an order of [TENANT] keeps under `key`, which sends `AddItems` to [target]. */
    private fun timer(target: AggregateRef, slot: Long = 1) = Timer(
        id = "shop.order/$TENANT/o-1#key@1",
        owner = AggregateType(SHOP, "order").ref(TENANT, "o-1"),
        key = "key",
        version = 1,
        partition = 0,
        rule = ScheduleRule.At(0),
        dueAt = 0,
        slot = slot,
        target = target,
        commandType = "${target.type}.AddItems",
        body = """{"items":["apple"]}""",
    )

    @Test
    fun `a timer's command that names its scheduler's tenant lands in the fixed one, another is rejected`(): Unit =
        runBlocking {
            firer.fire(timer(CART.ref(TENANT, "bob"))).assert().isEqualTo(TimerOutcomes.COMMITTED)
            log.read(CART.ref(SHARED, "bob")).toList().size.assert().isEqualTo(1)
            log.read(CART.ref(TENANT, "bob")).toList().assert().isEmpty()

            firer.fire(timer(CART.ref(OTHER, "bob"), slot = 2)).assert().isEqualTo(TimerOutcomes.REJECTED)
            log.read(CART.ref(OTHER, "bob")).toList().assert().isEmpty()
        }

    @Test
    fun `a creation that names no aggregate takes an id, and a slot fired twice creates one aggregate`(): Unit =
        runBlocking {
            val unassigned = timer(CART.unassigned(TENANT))
            // The request id is the slot's, `timer#slot`, every time it fires: a firing again finds what it created.
            unassigned.requestId.assert().isEqualTo("shop.order/$TENANT/o-1#key@1#1")
            firer.fire(unassigned).assert().isEqualTo(TimerOutcomes.COMMITTED)
            firer.fire(unassigned).assert().isEqualTo(TimerOutcomes.DUPLICATE)
            val created = log.findByRequestId(CART, SHARED, unassigned.requestId)!!
            created.ref.tenantId.assert().isEqualTo(SHARED)
            created.ref.isAssigned.assert().isTrue()
            log.read(created.ref).toList().size.assert().isEqualTo(1)

            // The next slot is another request, which creates another aggregate.
            firer.fire(timer(CART.unassigned(TENANT), slot = 2)).assert().isEqualTo(TimerOutcomes.COMMITTED)
            log.findByRequestId(CART, SHARED, "shop.order/$TENANT/o-1#key@1#2")!!.ref.assert().isNotEqualTo(created.ref)
        }

    @Test
    fun `a command to an aggregate whose id is its owner's takes the owner's, and is rejected without one`(): Unit =
        runBlocking {
            firer.fire(timer(wallet.type.unassigned(TENANT))).assert().isEqualTo(TimerOutcomes.REJECTED)
            firer.fire(timer(wallet.type.unassigned(TENANT), slot = 2).copyWith(scope = Scope("alice")))
                .assert().isEqualTo(TimerOutcomes.COMMITTED)
            log.read(wallet.type.ref(TENANT, "alice")).toList().size.assert().isEqualTo(1)
        }

    @Test
    fun `a command naming an id admission refuses, or taking such an owner's, is rejected`(): Unit = runBlocking {
        // #6594: a timer creates no aggregate the gateway's routes could not reach.
        firer.fire(timer(CART.ref(TENANT, "a/b"))).assert().isEqualTo(TimerOutcomes.REJECTED)
        firer.fire(timer(wallet.type.unassigned(TENANT)).copyWith(scope = Scope("a;b")))
            .assert().isEqualTo(TimerOutcomes.REJECTED)
        log.read(CART.ref(TENANT, "a/b")).toList().assert().isEmpty()
        log.read(wallet.type.ref(TENANT, "a;b")).toList().assert().isEmpty()
    }

    private companion object {
        const val SHARED = "shared"
        const val OTHER = "other"
    }
}

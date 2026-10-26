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

package me.ahoo.wow.api

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.ledger.ApplyParkedRetrySpec
import me.ahoo.wow.api.ledger.MarkParkedRecoverable
import me.ahoo.wow.api.ledger.ResumeParked
import me.ahoo.wow.api.ledger.SkipParked
import me.ahoo.wow.api.lifecycle.ApplyTags
import me.ahoo.wow.api.lifecycle.OwnerTransferred
import me.ahoo.wow.api.lifecycle.SpaceTransferred
import me.ahoo.wow.api.lifecycle.TagsApplied
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.lifecycle.TransferSpace
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.api.schedule.CancelSchedule
import me.ahoo.wow.api.schedule.EnableJob
import me.ahoo.wow.api.schedule.JobEnabled
import me.ahoo.wow.api.schedule.JobRescheduled
import me.ahoo.wow.api.schedule.JobRule
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.api.schedule.RescheduleJob
import me.ahoo.wow.api.schedule.ScheduleCancelled
import me.ahoo.wow.api.schedule.ScheduleContext
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.ScheduleSet
import me.ahoo.wow.api.schedule.ScheduleTick
import me.ahoo.wow.api.schedule.SetSchedule
import me.ahoo.wow.api.schedule.Ticked
import org.junit.jupiter.api.Test

/**
 * The frozen types users construct are plain classes (decision D227): built through their factories, they keep a
 * data class's equality, hash and string, and Wow's own copy replaces the fields it is given.
 */
class PlainValuesTest {
    private val ref = AggregateType("sales", "order").ref("tenant", "o-1")
    private val parked = AggregateType("payment", "payment").ref("tenant", "p-1")
    private val cron = ScheduleRule.Cron.of("0 0 3 * * *")

    @Test
    fun `the caller and the operator are values`() {
        val caller = Caller.of(
            tenantId = "tenant",
            scope = Scope(ownerId = "alice"),
            subject = "alice",
            attributes = mapOf("roles" to listOf("admin")),
        )
        valueSemantics({ Caller.of(tenantId = "tenant", subject = "alice") }, Caller.of(tenantId = "tenant"))
        caller.toString().assert().isEqualTo(
            "Caller(tenantId=tenant, scope=Scope(ownerId=alice, spaceId=null), subject=alice, crossScope=false, " +
                "attributes={roles=[admin]}, service=false)",
        )
        Caller.of().assert().isEqualTo(Caller.ANONYMOUS)
        caller.copyWith(tenantId = "other").tenantId.assert().isEqualTo("other")
        valueSemantics({ Operator.of("alice", "tenant") }, Operator.of("alice", "tenant", crossScope = true))
    }

    @Test
    fun `the schedule and job rules and their commands and events are values`() {
        valueSemantics({ ScheduleRule.At.of(1L) }, ScheduleRule.At.of(2L))
        valueSemantics({ ScheduleRule.After.of(1L) }, ScheduleRule.After.of(2L))
        valueSemantics({ ScheduleRule.Cron.of("0 0 3 * * *") }, ScheduleRule.Cron.of("0 0 3 * * *", "Asia/Shanghai"))
        cron.copyWith(misfire = Misfire.CATCH_UP).misfire.assert().isEqualTo(Misfire.CATCH_UP)
        valueSemantics(
            { SetSchedule.of("pay", cron, "sales.order.Pay", "{}") },
            SetSchedule.of("pay", cron, "a.b.C", "{}")
        )
        valueSemantics({ CancelSchedule.of("pay") }, CancelSchedule.of("ship"))
        valueSemantics(
            { ScheduleSet.of("pay", cron, ref, "sales.order.Pay", "{}") },
            ScheduleSet.of("ship", cron, ref, "sales.order.Pay", "{}"),
        )
        valueSemantics({ ScheduleCancelled.of("pay") }, ScheduleCancelled.of("ship"))
        valueSemantics({ ScheduleContext.of("t", "pay", 1, 2) }, ScheduleContext.of("t", "pay", 2, 2, 1))
        valueSemantics({ JobRule.of() }, JobRule.of("0 0 4 * * *"))
        valueSemantics({ EnableJob.of("nightly") }, EnableJob.of("nightly", JobRule.of(zone = "UTC"), 1))
        valueSemantics({ RescheduleJob.of() }, RescheduleJob.of(JobRule.of(misfire = Misfire.CATCH_UP)))
        valueSemantics({ JobEnabled.of("nightly", cron) }, JobEnabled.of("nightly", cron, JobRule.of(), 0))
        valueSemantics({ JobRescheduled.of(cron) }, JobRescheduled.of(cron, JobRule.of()))
        valueSemantics({ Ticked.of("t", 2, 1, 3) }, Ticked.of("t", 3, 1, 3))
        valueSemantics({ ScheduleTick.of("tenant", "nightly", 1) }, ScheduleTick.of("tenant", "nightly", 2, 1, 3, 0))
    }

    @Test
    fun `the system commands and events, decisions and result parts are values`() {
        valueSemantics({ TransferOwner.of("alice") }, TransferOwner.of("bob"))
        valueSemantics({ TransferSpace.of("space") }, TransferSpace.of("other"))
        valueSemantics({ ApplyTags.of(mapOf("tier" to listOf("gold"))) }, ApplyTags.of(emptyMap()))
        valueSemantics({ OwnerTransferred.of("alice") }, OwnerTransferred.of("bob"))
        valueSemantics({ SpaceTransferred.of("space") }, SpaceTransferred.of("other"))
        valueSemantics({ TagsApplied.of(mapOf("tier" to listOf("gold"))) }, TagsApplied.of(emptyMap()))
        valueSemantics({ ResumeParked.of("Saga.on", parked) }, ResumeParked.of("Saga.other", parked))
        valueSemantics({ SkipParked.of("Saga.on", parked) }, SkipParked.of("Saga.other", parked))
        valueSemantics(
            { MarkParkedRecoverable.of("Saga.on", parked, "RECOVERABLE") },
            MarkParkedRecoverable.of("Saga.on", parked, "UNRECOVERABLE"),
        )
        valueSemantics(
            { ApplyParkedRetrySpec.of("Saga.on", parked) },
            ApplyParkedRetrySpec.of("Saga.on", parked, 3)
        )
        valueSemantics({ Decision.Events.of(listOf("placed")) }, Decision.Events.of(listOf("paid")))
        valueSemantics({ Decision.Rejected.of(Rejection.of("a", "b")) }, Decision.Rejected.of(Rejection.of("c", "d")))
        valueSemantics(
            { FunctionInfo.of(FunctionKind.COMMAND, "sales", "order", "Place") },
            FunctionInfo.of(FunctionKind.SAGA, "sales", "order", "Place"),
        )
        FunctionInfo.of(FunctionKind.COMMAND, "sales", "order", "Place").copyWith(name = "Pay").name.assert()
            .isEqualTo("Pay")
        valueSemantics({ Violation.of("items", "min", "too few") }, Violation.of("items", "max", "too many"))
    }

    private fun valueSemantics(make: () -> Any, other: Any) {
        val value = make()
        val same = make()
        (value == value).assert().isTrue()
        (value == same).assert().isTrue()
        value.hashCode().assert().isEqualTo(same.hashCode())
        (value == other).assert().isFalse()
        (value == value.javaClass.simpleName).assert().isFalse()
        value.toString().assert().startsWith("${value.javaClass.simpleName}(").isEqualTo(same.toString())
    }
}

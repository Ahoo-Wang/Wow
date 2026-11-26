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

package me.ahoo.wow.kernel.review

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.log.EventRecord
import org.junit.jupiter.api.Test

/**
 * Round two, the upgrade path (D487 ① third item): two versions side by side. `Misfire`'s KDoc says cases may be
 * added in a minor release, and the upgrade page says a reader older than its writer reads a value it does not know
 * leniently. A node of the minor before reads the log its newer neighbour wrote during the rollout: an aggregate whose
 * cron schedule names a misfire case this node does not know should still decide its commands.
 */
class R2UnknownMisfireTest {
    data class Place(val at: Long)
    data class Report(val expression: String)
    data class Pay(val amount: Int)
    data class Placed(val at: Long)
    data class Paid(val amount: Int)
    data class Notify(val text: String)

    class Order {
        var paid = 0
    }

    private val model = aggregate<Order>("test", "order") {
        initial { Order() }
        decide<Place>(Creation.REQUIRED) { _, body -> Decision.events(Placed(body.at)) }
        decide<Report> { _, body ->
            schedule("report", ScheduleRule.cron(body.expression), Notify("report"))
            Decision.NoOp
        }
        decide<Pay> { _, body -> Decision.events(Paid(body.amount)) }
        decide<Notify> { _, _ -> Decision.NoOp }
        evolve<Placed> { }
        evolve<Paid> { paid += it.amount }
    }
    private val ref = model.type.ref("t", "o-1")
    private var sequence = 0

    private suspend fun Kernel.send(body: Any) =
        execute(model, Command("c-${++sequence}", "r-$sequence", ref, body))

    @Test
    fun `an aggregate whose schedule names a misfire this node does not know should still decide its commands`(): Unit =
        runTest {
            // The newer node: the aggregate and its cron schedule, as written today.
            val newer = InMemoryEventLog(partitions = 1)
            val writer = Kernel(newer, clock = { NOW })
            writer.send(Place(NOW)).assert().isInstanceOf(CommandOutcome.Committed::class.java)
            writer.send(Report("0 0 0 * * ?")).assert().isInstanceOf(CommandOutcome.Committed::class.java)
            val streams = newer.read(ref).toList()
            streams.last().events.single().name.assert().isEqualTo(Schedules.SCHEDULE_SET)
            streams.last().events.single().payload.assert().contains(""""misfire":"coalesce"""")

            // The same log as a later minor writes it, with a misfire case it added.
            val log = InMemoryEventLog(partitions = 1)
            streams.forEach { stream ->
                log.append(
                    stream.copyWith(
                        events = stream.events.map { event ->
                            EventRecord.of(
                                event.name,
                                event.revision,
                                event.payload.replace(""""misfire":"coalesce"""", """"misfire":"fire-once"""")
                            )
                        }
                    )
                )
            }

            // This node, the minor before, still running during the rollout.
            val older = Kernel(log, clock = { NOW })
            val outcome = older.send(Pay(5))
            outcome.assert().isInstanceOf(CommandOutcome.Committed::class.java)
        }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}

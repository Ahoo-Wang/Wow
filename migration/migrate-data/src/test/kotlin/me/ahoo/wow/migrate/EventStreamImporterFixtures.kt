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

package me.ahoo.wow.migrate

import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.log.EventLog

internal abstract class EventStreamImporterFixtures {
    protected val order = NineXAggregate("example-service", "order")
    protected val cart = NineXAggregate("example-service", "cart")
    protected val log = InMemoryEventLog(4)
    protected val checkpoints = InMemoryCheckpointRepository()
    protected var now = 1_000L
    protected val reports = mutableListOf<ImportProgress>()

    protected fun importer(
        source: NineXEventSource,
        options: ImportOptions = ImportOptions(),
        target: EventLog = log,
        plan: MigrationPlan = NineXExample.plan,
    ) = EventStreamImporter(source, plan, { target }, checkpoints, options, { now }, { reports += it })

    protected fun copyOf(stream: NineXStream, id: String, version: Int = stream.version, createTime: Long = stream.createTime) =
        stream.copy(
            id = "${stream.id}-$id",
            aggregateId = stream.aggregateId.copy(id = id),
            commandId = "${stream.commandId}-$id",
            requestId = "${stream.requestId}-$id",
            version = version,
            createTime = createTime,
        )
}

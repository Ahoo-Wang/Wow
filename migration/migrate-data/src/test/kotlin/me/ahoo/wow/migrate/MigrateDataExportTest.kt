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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.log.EventRecord
import org.junit.jupiter.api.Test

internal class MigrateDataExportTest : MigrateDataFixtures() {
    @Test
    fun `export writes back what V10 wrote after the cut-over, and refuses a V10-only type`(): Unit = runBlocking {
        val config = config().toString()
        run("import", "--config", config).assert().isEqualTo(MigrateData.OK)
        val last = StreamConverter(NineXExample.plan).convert(NineXExample.stream("order-stream-v4.json"))
        log.append(
            last.copyWith(
                version = 5,
                commandId = "v10-receive",
                requestId = "v10-receive",
                events = listOf(EventRecord("example.order.OrderReceived", 1, "{}")),
            ),
        )
        run("export", "--config", config, "--dry-run").assert().isEqualTo(MigrateData.OK)
        printed.assert().contains("Dry run done: 1 streams would be exported; nothing was written.")
        exportTarget.written.assert().isEmpty()

        run("export", "--config", config).assert().isEqualTo(MigrateData.OK)
        printed.assert().contains("example done: 1 streams exported to 9.x, 8 already there")
            .contains("EQUAL example-service.order <- example.order: V10 5 streams, 9.x 5 streams")
            .contains("Export done: 9.x holds every stream V10 holds; start 9.x again.")
        FileCheckpointRepository(directory.resolve("migrate-data.checkpoint.json")).load()
            .exportOf(NineXExample.CONTEXT)!!.exported.assert().isEqualTo(1)

        source.add(
            NineXExample.stream("cart-stream-v1.json").let {
                it.copy(id = "9x-only", aggregateId = it.aggregateId.copy(id = "9x-cart"))
            }
        )
        run("export", "--config", config).assert().isEqualTo(MigrateData.DIFFERS)
        printed.assert().contains("DIFFERS example-service.cart").contains("hold different numbers of streams")

        log.append(
            last.copyWith(
                version = 6,
                commandId = "v10-v2",
                requestId = "v10-v2",
                events = listOf(EventRecord("example.order.OrderCreated", 2, "{}")),
            ),
        )
        run("export", "--config", config).assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains("REFUSED the V10 event example.order.OrderCreated@2").contains("Export refused")
        exportTarget.written.size.assert().isEqualTo(1)
        run("export", "--config", config, "--snapshots").assert().isEqualTo(MigrateData.USAGE_ERROR)
    }

    @Test
    fun `a dry-run export reads the checkpoints kept in the target, and takes no lease`(): Unit = runBlocking {
        val config = config(checkpoint = "target:example").toString()
        run("import", "--config", config).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        val before = checkpointTable.load("example")!!
        run("export", "--config", config, "--dry-run").assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("Dry run done")
        checkpointTable.load("example").assert().isEqualTo(before)
        // The export role was never taken: its first acquisition is at epoch 1.
        leaseStore.acquire(
            "${TargetCheckpointLeases.GROUP_PREFIX}example",
            CheckpointRole.EXPORT.partition,
            "probe",
            TargetCheckpointLeases.TTL
        )!!
            .epoch.assert().isEqualTo(1)
    }
}

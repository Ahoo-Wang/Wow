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

package me.ahoo.wow.bi.layout

import me.ahoo.test.asserts.assert
import me.ahoo.wow.bi.BiObjectKey
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ClickHouseTopology
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test

class BiLayoutTest {
    private val aggregate = MaterializedNamedAggregate("example-service", "order")

    @Test
    fun `should name every object of an aggregate`() {
        val layout = BiLayout(BiScriptOptions(topology = ClickHouseTopology.Standalone)).of(aggregate)

        layout.command.assert().isEqualTo(
            BiStreamLayout(
                table = "example_order_command",
                store = "example_order_command_store",
                queue = "example_order_command_queue",
                consumer = "example_order_command_consumer",
                topic = "wow.example.order.command",
                schema = BiStoreSchema.COMMAND,
            )
        )
        layout.state.store.assert().isEqualTo("example_order_state_store")
        layout.state.schema.assert().isEqualTo(BiStoreSchema.STATE)
        layout.stateEvent.assert().isEqualTo("example_order_state_event")
        layout.stateLast.assert().isEqualTo("example_order_state_last")
        layout.stateLastStore.assert().isEqualTo("example_order_state_last_store")
        layout.stateLastConsumer.assert().isEqualTo("example_order_state_last_consumer")
    }

    @Test
    fun `should place objects in their databases and stores in their topology`() {
        val standalone = BiLayout(BiScriptOptions(topology = ClickHouseTopology.Standalone))
        val cluster = BiLayout(BiScriptOptions(topology = ClickHouseTopology.Cluster()))

        standalone.anchor.assert().isEqualTo(BiObjectKey("bi_db_consumer", "__wow_bi_deployment"))
        standalone.storeKey("s").assert().isEqualTo(BiObjectKey("bi_db", "s"))
        standalone.viewKey("v").assert().isEqualTo(BiObjectKey("bi_db", "v"))
        standalone.ingressKey("q").assert().isEqualTo(BiObjectKey("bi_db_consumer", "q"))
        standalone.physicalStore("s").assert().isEqualTo("s")
        standalone.storeEngine.assert().isEqualTo(BiEngine.REPLACING_MERGE_TREE)
        cluster.physicalStore("s").assert().isEqualTo("s_local")
        cluster.storeEngine.assert().isEqualTo(BiEngine.DISTRIBUTED)
        BiLayout.isLocalStore("s_local").assert().isTrue()
        BiLayout.logicalStore("s_local").assert().isEqualTo("s")
    }

    @Test
    fun `should recover streams and schemas from object names`() {
        BiLayout.streamOfQueue("example_order_command_queue").assert()
            .isEqualTo(BiQueueStream("command", "example_order_command_consumer"))
        BiLayout.streamOfQueue("example_order_state_queue").assert()
            .isEqualTo(BiQueueStream("state", "example_order_state_consumer"))
        BiLayout.streamOfQueue("example_order_queue").assert().isNull()
        BiStoreSchema.ofStore("example_order_command_store").assert().isEqualTo(BiStoreSchema.COMMAND)
        BiStoreSchema.ofStore("example_order_state_store").assert().isEqualTo(BiStoreSchema.STATE)
        BiStoreSchema.ofStore("example_order_state_last_store").assert().isEqualTo(BiStoreSchema.STATE_LAST)
        BiStoreSchema.ofStore("example_order_store").assert().isNull()
    }

    @Test
    fun `should report store keys in the catalog's notation`() {
        BiStoreSchema.COMMAND.catalogPartitionKey.assert().isEqualTo("toYYYYMM(create_time)")
        BiStoreSchema.COMMAND.catalogSortingKey.assert().isEqualTo("id")
        BiStoreSchema.COMMAND.catalogShardingKey.assert().isEqualTo("sipHash64(aggregate_id)")
        BiStoreSchema.STATE.catalogSortingKey.assert().isEqualTo("tenant_id, aggregate_id, version")
        BiStoreSchema.STATE_LAST.catalogPartitionKey.assert().isEqualTo("toYYYYMM(first_event_time)")
        BiStoreSchema.STATE_LAST.catalogShardingKey.assert().isEqualTo("sipHash64(tenant_id, aggregate_id)")
        BiStoreSchema.STATE.columns("DT").last().assert().isEqualTo(BiStoreColumn("deleted", "Bool"))
    }
}

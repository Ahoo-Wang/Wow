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

package me.ahoo.wow.bi.expansion

import me.ahoo.test.asserts.assert
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import org.junit.jupiter.api.Test

class BiTableNamingTest {
    private val biAggregateMetadata = aggregateMetadata<BIAggregate, BIAggregateState>()

    @Test
    fun `should build options-aware topic name`() {
        val naming = BiTableNaming(BiScriptOptions(topicPrefix = "custom."))

        naming.toTopicName(biAggregateMetadata, "state").assert().isEqualTo("custom.bi.aggregate.state")
    }

    @Test
    fun `should build logical table name`() {
        val naming = BiTableNaming()

        naming.toTableName(biAggregateMetadata, "state_last").assert()
            .isEqualTo("bi_aggregate_state_last")
    }

    @Test
    fun `should remove service only when it is the terminal suffix`() {
        val naming = BiTableNaming()

        naming.toTableName(MaterializedNamedAggregate("foo-service", "order"), "state")
            .assert().isEqualTo("foo_order_state")
        naming.toTableName(MaterializedNamedAggregate("foo-service-v2", "order"), "state")
            .assert().isEqualTo("foo_service_v2_order_state")
    }

    @Test
    fun `should normalize dotted context aliases for ClickHouse object names only`() {
        val naming = BiTableNaming(BiScriptOptions(topicPrefix = "custom."))
        val aggregate = MaterializedNamedAggregate("wow.api.command", "order")

        naming.toTableName(aggregate, "state").assert()
            .isEqualTo("wow_api_command_order_state")
        naming.toTopicName(aggregate, "state").assert()
            .isEqualTo("custom.wow.api.command.order.state")
    }
}

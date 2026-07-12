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
    fun `should build distributed table name`() {
        val naming = BiTableNaming()

        naming.toDistributedTableName(biAggregateMetadata, "state_last").assert()
            .isEqualTo("bi_aggregate_state_last")
    }
}

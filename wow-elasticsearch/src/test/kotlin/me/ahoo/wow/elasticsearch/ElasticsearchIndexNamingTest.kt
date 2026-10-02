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

package me.ahoo.wow.elasticsearch

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ElasticsearchIndexNamingTest {
    private val order = MaterializedNamedAggregate("unregistered-context", "order")

    /** The names 9.1 and 9.2 nodes share on one cluster: without a prefix they must never change. */
    @Test
    fun `default names are pinned`() {
        val naming = ElasticsearchIndexNaming.DEFAULT
        naming.prefix.assert().isEmpty()
        naming.snapshotIndexName(order).assert().isEqualTo("wow.unregistered-context.order.snapshot")
        naming.eventStreamIndexName(order).assert().isEqualTo("wow.unregistered-context.order.es")
        order.toSnapshotIndexName().assert().isEqualTo("wow.unregistered-context.order.snapshot")
        order.toEventStreamIndexName().assert().isEqualTo("wow.unregistered-context.order.es")
        naming.resolve("wow-snapshot-template").assert().isEqualTo("wow-snapshot-template")
        naming.resolve("wow.*.es").assert().isEqualTo("wow.*.es")
        ElasticsearchIndexNaming().snapshotIndexName(MOCK_AGGREGATE_METADATA)
            .assert().isEqualTo(MOCK_AGGREGATE_METADATA.toSnapshotIndexName())
        ElasticsearchIndexNaming().eventStreamIndexName(MOCK_AGGREGATE_METADATA)
            .assert().isEqualTo(MOCK_AGGREGATE_METADATA.toEventStreamIndexName())
    }

    @Test
    fun `a prefix goes before every name`() {
        val naming = ElasticsearchIndexNaming("staging.")
        naming.snapshotIndexName(order).assert().isEqualTo("staging.wow.unregistered-context.order.snapshot")
        naming.eventStreamIndexName(order).assert().isEqualTo("staging.wow.unregistered-context.order.es")
        naming.resolve("wow-event-stream-template").assert().isEqualTo("staging.wow-event-stream-template")
        naming.resolve("wow.*.snapshot").assert().isEqualTo("staging.wow.*.snapshot")
    }

    @ParameterizedTest
    @ValueSource(strings = ["staging.", "dev-", "team1_", "wow", "wowx.", "a+b.", "é."])
    fun `a valid prefix is accepted`(prefix: String) {
        ElasticsearchIndexNaming(prefix).prefix.assert().isEqualTo(prefix)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Staging.", "a\\b", "a/b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "a,b", "a#b", "a:b", "a b", "a\tb",
            "-a", "_a", "+a", ".a", "wow.", "wow.dev.",
        ],
    )
    fun `an invalid prefix fails fast`(prefix: String) {
        assertThrownBy<IllegalArgumentException> {
            ElasticsearchIndexNaming(prefix)
        }.hasMessageContaining("[$prefix]")
    }
}

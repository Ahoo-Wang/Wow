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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.elasticsearch.events.ElasticsearchEventDocuments
import me.ahoo.wow.elasticsearch.events.ElasticsearchEventIndex
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotDocuments
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import org.junit.jupiter.api.Test

/** The fields a document of this format names, read and written without a pre-release's default (D414). */
class ElasticsearchDocumentFieldsTest {
    private fun record(accessVersion: Int?) = StateEventRecord.of(
        AggregateType("shop", "order").ref("tenant", "o-1"),
        3,
        commandId = "c-1",
        requestId = "r-1",
        commandType = "shop.order.Place",
        commitTime = 1_000L,
        events = listOf(StateEventPayload.of("shop.order.Placed", 1, WowJson.mapper.readTree("{}"))),
        access = mapOf("region" to "eu"),
        accessVersion = accessVersion,
    )

    @Test
    fun `an events document should write the version its access attributes are of, never one in its place`() {
        ElasticsearchEventDocuments.document(record(accessVersion = 2)).path(ElasticsearchEventIndex.ACCESS_VERSION)
            .intValue().assert().isEqualTo(2)
        assertThrownBy<IllegalStateException> { ElasticsearchEventDocuments.document(record(accessVersion = null)) }
            .message().contains("shop.order/tenant/o-1@3 holds access attributes without the version they are of")
    }

    @Test
    fun `a snapshot document should name its state revision, never read as 1 when it does not`() {
        val document = WowJson.mapper.createObjectNode().put(ElasticsearchSnapshotDocuments.AGGREGATE_ID, "o-1")
        assertThrownBy<IllegalStateException> { ElasticsearchSnapshotDocuments.stateRevisionOf(document) }
            .message().contains("A snapshot document names its stateRevision; this one does not")
        document.put(ElasticsearchSnapshotDocuments.STATE_REVISION, 2)
        ElasticsearchSnapshotDocuments.stateRevisionOf(document).assert().isEqualTo(2)
    }
}

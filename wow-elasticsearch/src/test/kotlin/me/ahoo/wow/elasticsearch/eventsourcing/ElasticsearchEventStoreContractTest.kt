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

package me.ahoo.wow.elasticsearch.eventsourcing

import me.ahoo.test.asserts.assert
import me.ahoo.wow.tck.architecture.DefaultMethodContract
import org.junit.jupiter.api.Test

/**
 * `EventStore.existsRequestId` defaults to loading the whole stream. A storage implementation must override it
 * with a bounded lookup, because the idempotency check calls it on the command path.
 */
class ElasticsearchEventStoreContractTest {
    @Test
    fun `event store should override existsRequestId`() {
        val inherited = DefaultMethodContract.inheritedDefaults(ElasticsearchEventStore::class.java)
            .filter { it.startsWith("ElasticsearchEventStore.existsRequestId(") }

        inherited.assert()
            .describedAs("Inherited full-stream fallbacks; remove an entry from KNOWN_GAPS once it is fixed.")
            .isEqualTo(KNOWN_GAPS)
    }

    companion object {
        /**
         * Gaps that exist today, removed by 9.3.0 WP S5. The list may only shrink.
         */
        val KNOWN_GAPS: List<String> = listOf(
            "ElasticsearchEventStore.existsRequestId(AggregateId,String)",
        )
    }
}

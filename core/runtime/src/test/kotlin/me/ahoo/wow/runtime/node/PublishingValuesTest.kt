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

package me.ahoo.wow.runtime.node

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.ledger.LedgerPublishing
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** The node's publishing values (finding R5.A-24): made by `of`, equal by their fields, copied and said by name. */
class PublishingValuesTest {
    @Test
    fun `the ledger's publishing is made by of, equal by its fields`() {
        val publishing = LedgerPublishing.of("ops", 5.seconds)
        publishing.assert().isEqualTo(publishing).isEqualTo(LedgerPublishing.of("ops", 5.seconds, false))
            .isNotEqualTo(LedgerPublishing.of("ops", 5.seconds, true)).isNotEqualTo("ops")
        publishing.hashCode().assert().isEqualTo(LedgerPublishing.of("ops", 5.seconds).hashCode())
        publishing.toString().assert().isEqualTo("LedgerPublishing(context=ops, interval=5s, automatic=false)")
    }

    @Test
    fun `the source topics are made by of and copied`() {
        val broker = InMemoryTopics()
        val topics = SourceTopics.of(broker, "p.", broker)
        topics.assert().isEqualTo(topics).isEqualTo(SourceTopics.of(broker, "p.", broker)).isNotEqualTo("p.")
        topics.hashCode().assert().isEqualTo(SourceTopics.of(broker, "p.", broker).hashCode())
        topics.copyWith(intake = true).assert().isNotEqualTo(topics)
        topics.copyWith(partitionRefresh = 1.seconds).partitionRefresh.assert().isEqualTo(1.seconds)
        topics.toString().assert().startsWith("SourceTopics(")
    }
}

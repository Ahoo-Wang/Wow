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

package me.ahoo.wow.mongo.log

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class LastSequencesSizingTest {
    private val gib = 1L shl 30

    @Test
    fun `a log remembers as many aggregates as the state caches hold at most`() {
        // A tenth of 1 GiB at 1 KB a state.
        MongoEventLog.lastSequencesFor(gib).assert().isEqualTo(104_857)
        MongoEventLog.lastSequencesFor(8 * gib).assert().isEqualTo(838_860)
    }

    @Test
    fun `a small heap still remembers the fewest`() {
        MongoEventLog.lastSequencesFor(256L shl 20).assert().isEqualTo(MongoEventLog.LAST_SEQUENCES)
    }
}

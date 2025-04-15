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

package me.ahoo.wow.serialization.event

import com.fasterxml.jackson.databind.node.ObjectNode
import me.ahoo.wow.api.Identifier
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.serialization.toJsonString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StateJsonRecordTest {
    @Test
    fun toState() {
        val state = MockState()
        val stateNode = state.toJsonString().toJsonNode<ObjectNode>()
        val stateJsonRecord = StateJsonRecord(stateNode)
        assertThat(stateJsonRecord.state<MockState>()).isEqualTo(state)
        assertThat(stateJsonRecord.actual).isEqualTo(stateNode)
    }

    data class MockState(override val id: String = generateGlobalId()) : Identifier
}

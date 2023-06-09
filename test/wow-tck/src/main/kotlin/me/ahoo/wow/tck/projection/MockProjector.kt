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

package me.ahoo.wow.tck.projection

import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.slf4j.LoggerFactory

class MockProjector {
    companion object {
        private val log = LoggerFactory.getLogger(MockProjector::class.java)
    }

    fun onEvent(created: MockAggregateCreated) {
        log.info(created.toString())
    }

    @Suppress("UNUSED_PARAMETER")
    fun onStateEvent(changed: MockAggregateChanged, state: ReadOnlyStateAggregate<MockStateAggregate>) {
        log.info(changed.toString())
    }
}

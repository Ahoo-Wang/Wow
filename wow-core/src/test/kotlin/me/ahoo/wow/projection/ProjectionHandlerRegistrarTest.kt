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

package me.ahoo.wow.projection

import me.ahoo.wow.configuration.asNamedAggregate
import me.ahoo.wow.tck.modeling.AggregateChanged
import me.ahoo.wow.tck.modeling.AggregateCreated
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test

internal class ProjectionProcessorRegistrarTest {

    @Test
    fun registerProjectionProcessor() {
        val handlerRegistrar = ProjectionFunctionRegistrar()
        val namedAggregate = AggregateCreated::class.java.asNamedAggregate()
        val mockProjector = MockProjector()
        handlerRegistrar.registerProcessor(mockProjector)
        assertThat(handlerRegistrar.namedAggregates, hasSize(1))
        assertThat(handlerRegistrar.namedAggregates, hasItem(namedAggregate))

        assertThat(
            handlerRegistrar.getFunctions(AggregateCreated::class.java),
            hasSize(1),
        )
        assertThat(
            handlerRegistrar.getFunctions(AggregateChanged::class.java),
            hasSize(1),
        )
    }
}

internal class MockProjector {
    lateinit var state: String

    fun onEvent(created: AggregateCreated) {
        state = created.state
    }

    fun onEvent(changed: AggregateChanged) {
        state = changed.state
    }
}

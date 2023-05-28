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
package me.ahoo.wow.tck.modeling.state

import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

abstract class StateAggregateFactorySpec {
    private val aggregateMetadata = aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
    protected abstract fun createStateAggregateFactory(): StateAggregateFactory

    @Test
    fun create() {
        val aggregateFactory = createStateAggregateFactory()
        val aggregateId = aggregateMetadata.asAggregateId(id = GlobalIdGenerator.generateAsString())
        aggregateFactory.create(aggregateMetadata.state, aggregateId)
            .test()
            .consumeNextWith { stateAggregate: StateAggregate<MockStateAggregate> ->
                MatcherAssert.assertThat(stateAggregate, notNullValue())
                MatcherAssert.assertThat(stateAggregate.aggregateId.id, equalTo(aggregateId.id))
            }
            .verifyComplete()
    }
}

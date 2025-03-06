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
package me.ahoo.wow.modeling.annotation

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * AnnotationAggregateParserTest .
 *
 * @author ahoo wang
 */
internal class AggregateMetadataParserTest {
    @Test
    fun parse() {
        val aggregateMetadata =
            aggregateMetadata<MockAggregate, MockAggregate>()
        assertThat(aggregateMetadata, notNullValue())
        assertThat(
            aggregateMetadata.state.aggregateType,
            equalTo(
                MockAggregate::class.java,
            ),
        )
        assertThat(aggregateMetadata.isAggregationPattern, equalTo(false))
    }

    @Test
    fun parseCombined() {
        val aggregateMetadata = aggregateMetadata<MockCommandAggregate, me.ahoo.wow.tck.mock.MockStateAggregate>()
        assertThat(aggregateMetadata, notNullValue())
        assertThat(
            aggregateMetadata.command.aggregateType,
            equalTo(
                MockCommandAggregate::class.java,
            ),
        )
        assertThat(
            aggregateMetadata.state.aggregateType,
            equalTo(
                MockStateAggregate::class.java,
            ),
        )
        assertThat(aggregateMetadata.isAggregationPattern, equalTo(true))
    }

    @Test
    fun parseWhenWithAggregateId() {
        val aggregateMetadata = aggregateMetadata<MockAggregateWithAggregateId, MockAggregateWithAggregateId>()
        assertThat(aggregateMetadata, notNullValue())
        assertThat(
            aggregateMetadata.state.aggregateType,
            equalTo(
                MockAggregateWithAggregateId::class.java,
            ),
        )
    }

    @Test
    fun parseWhenWithoutConstructor() {
        Assertions.assertThrows(IllegalStateException::class.java) {
            aggregateMetadata<MockAggregateWithoutConstructor, MockAggregateWithoutConstructor>()
        }
    }

    @Test
    fun parseMountAggregate() {
        val aggregateMetadata = aggregateMetadata<MockMountAggregate, MockMountAggregate>()
        assertThat(aggregateMetadata.command.mountedCommands, hasSize(1))
        val mountCommand = aggregateMetadata.command.mountedCommands.first()
        assertThat(mountCommand, equalTo(MockMountCommand::class.java))
    }

    @Test
    fun parseAfterCommandAggregate() {
        val aggregateMetadata = aggregateMetadata<MockAfterCommandAggregate, MockAfterCommandAggregate>()
        assertThat(aggregateMetadata.command.afterCommandFunction, notNullValue())
    }

    @Test
    fun parseDefaultAfterCommandAggregate() {
        val aggregateMetadata = aggregateMetadata<MockDefaultAfterCommandAggregate, MockDefaultAfterCommandAggregate>()
        assertThat(aggregateMetadata.command.afterCommandFunction, notNullValue())
    }

    @Test
    fun parseMockMultipleAfterCommandAggregate() {
        Assertions.assertThrows(IllegalStateException::class.java) {
            aggregateMetadata<MockMultipleAfterCommandAggregate, MockMultipleAfterCommandAggregate>()
        }
    }
}

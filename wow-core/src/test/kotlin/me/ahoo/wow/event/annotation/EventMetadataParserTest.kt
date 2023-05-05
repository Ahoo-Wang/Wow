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
package me.ahoo.wow.event.annotation

import me.ahoo.wow.api.event.DEFAULT_REVISION
import me.ahoo.wow.event.MockNamedAndRevisedEvent
import me.ahoo.wow.event.MockNamedEmptyEvent
import me.ahoo.wow.event.MockNamedEvent
import me.ahoo.wow.event.NAMED_EVENT
import me.ahoo.wow.event.REVISED_EVENT
import me.ahoo.wow.tck.event.MockEvent
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test

internal class EventMetadataParserTest {
    @Test
    fun parse() {
        val eventMetadata = eventMetadata<MockEvent>()
        assertThat(eventMetadata, notNullValue())
        assertThat(
            eventMetadata.eventType,
            equalTo(
                MockEvent::class.java,
            ),
        )
        assertThat(eventMetadata.revision, equalTo(DEFAULT_REVISION))
    }

    @Test
    fun parseWhenNamed() {
        val eventMetadata = eventMetadata<MockNamedEvent>()
        assertThat(eventMetadata, notNullValue())
        assertThat(
            eventMetadata.eventType,
            equalTo(
                MockNamedEvent::class.java,
            ),
        )
        assertThat(eventMetadata.name, equalTo(NAMED_EVENT))
        assertThat(eventMetadata.revision, equalTo(DEFAULT_REVISION))
    }

    @Test
    fun parseWhenNamedEmpty() {
        val eventMetadata = eventMetadata<MockNamedEmptyEvent>()
        assertThat(eventMetadata, notNullValue())
        assertThat(
            eventMetadata.eventType,
            equalTo(
                MockNamedEmptyEvent::class.java,
            ),
        )
        assertThat(eventMetadata.revision, equalTo(DEFAULT_REVISION))
    }

    @Test
    fun parseWhenNamedAndRevised() {
        val eventMetadata = eventMetadata<MockNamedAndRevisedEvent>()
        assertThat(eventMetadata, notNullValue())
        assertThat(
            eventMetadata.eventType,
            equalTo(
                MockNamedAndRevisedEvent::class.java,
            ),
        )
        assertThat(eventMetadata.name, equalTo(NAMED_EVENT))
        assertThat(eventMetadata.revision, equalTo(REVISED_EVENT))
    }
}

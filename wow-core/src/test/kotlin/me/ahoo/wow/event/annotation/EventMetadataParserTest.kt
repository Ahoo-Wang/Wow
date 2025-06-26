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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.event.DEFAULT_REVISION
import me.ahoo.wow.event.MockNamedAndRevisedEvent
import me.ahoo.wow.event.MockNamedEmptyEvent
import me.ahoo.wow.event.MockNamedEvent
import me.ahoo.wow.event.NAMED_EVENT
import me.ahoo.wow.event.REVISED_EVENT
import me.ahoo.wow.tck.mock.MockAggregateCreated
import org.junit.jupiter.api.Test

internal class EventMetadataParserTest {
    @Test
    fun parse() {
        val eventMetadata = eventMetadata<MockAggregateCreated>()
        eventMetadata.assert().isNotNull()
        eventMetadata.eventType.assert().isEqualTo(MockAggregateCreated::class.java)
        eventMetadata.revision.assert().isEqualTo(DEFAULT_REVISION)
        eventMetadata.hashCode().assert().isNotEqualTo(0)
        eventMetadata.toString().assert().isNotNull()
    }

    @Test
    fun parseWhenNamed() {
        val eventMetadata = eventMetadata<MockNamedEvent>()
        eventMetadata.assert().isNotNull()
        eventMetadata.eventType.assert().isEqualTo(MockNamedEvent::class.java)
        eventMetadata.name.assert().isEqualTo(NAMED_EVENT)
        eventMetadata.revision.assert().isEqualTo(DEFAULT_REVISION)
    }

    @Test
    fun parseWhenNamedEmpty() {
        val eventMetadata = eventMetadata<MockNamedEmptyEvent>()
        eventMetadata.assert().isNotNull()
        eventMetadata.eventType.assert().isEqualTo(MockNamedEmptyEvent::class.java)
        eventMetadata.revision.assert().isEqualTo(DEFAULT_REVISION)
    }

    @Test
    fun parseWhenNamedAndRevised() {
        val eventMetadata = eventMetadata<MockNamedAndRevisedEvent>()
        eventMetadata.assert().isNotNull()
        eventMetadata.eventType.assert().isEqualTo(MockNamedAndRevisedEvent::class.java)
        eventMetadata.name.assert().isEqualTo(NAMED_EVENT)
        eventMetadata.revision.assert().isEqualTo(REVISED_EVENT)
    }

    @Test
    fun eq() {
        val eventMetadata = eventMetadata<MockAggregateCreated>()
        eventMetadata.assert().isEqualTo(eventMetadata)
        eventMetadata.assert().isNotEqualTo(Any())
        val eventMetadata2 = eventMetadata<MockNamedAndRevisedEvent>()
        eventMetadata.assert().isNotEqualTo(eventMetadata2)
        val eventMetadata3 = eventMetadata<MockAggregateCreated>()
        eventMetadata.assert().isEqualTo(eventMetadata3)
    }
}

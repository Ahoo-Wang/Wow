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
import me.ahoo.wow.event.FIXTURE_EVENT_NAME
import me.ahoo.wow.event.FIXTURE_EVENT_REVISION
import me.ahoo.wow.event.FixtureNamedEvent
import me.ahoo.wow.event.FixtureRevisedEvent
import me.ahoo.wow.event.FixtureRoutedEvent
import org.junit.jupiter.api.Test

class EventMetadataParserTest {

    @Test
    fun `metadata uses event annotations for name and revision`() {
        val namedMetadata = eventMetadata<FixtureNamedEvent>()
        val revisedMetadata = eventMetadata<FixtureRevisedEvent>()

        namedMetadata.eventType.assert().isEqualTo(FixtureNamedEvent::class.java)
        namedMetadata.name.assert().isEqualTo(FIXTURE_EVENT_NAME)
        namedMetadata.revision.assert().isEqualTo(DEFAULT_REVISION)
        revisedMetadata.eventType.assert().isEqualTo(FixtureRevisedEvent::class.java)
        revisedMetadata.name.assert().isEqualTo(FIXTURE_EVENT_NAME)
        revisedMetadata.revision.assert().isEqualTo(FIXTURE_EVENT_REVISION)
    }

    @Test
    fun `metadata discovers aggregate id and aggregate name accessors`() {
        val event = FixtureRoutedEvent(id = "aggregate-1")
        val metadata = eventMetadata<FixtureRoutedEvent>()
        val namedAggregate = checkNotNull(metadata.namedAggregateGetter).getNamedAggregate(event)

        checkNotNull(metadata.aggregateIdGetter)[event].assert().isEqualTo("aggregate-1")
        namedAggregate.contextName.assert().isEqualTo("event")
        namedAggregate.aggregateName.assert().isEqualTo("fixture")
    }

    @Test
    fun `metadata equality is based on event type`() {
        val metadata = eventMetadata<FixtureNamedEvent>()

        metadata.assert().isEqualTo(eventMetadata<FixtureNamedEvent>())
        metadata.assert().isNotEqualTo(eventMetadata<FixtureRevisedEvent>())
        metadata.toString().assert().isEqualTo("EventMetadata(eventType=class me.ahoo.wow.event.FixtureNamedEvent)")
    }
}

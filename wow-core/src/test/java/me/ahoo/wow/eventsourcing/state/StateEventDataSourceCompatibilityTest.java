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

package me.ahoo.wow.eventsourcing.state;

import me.ahoo.wow.event.DomainEventStream;
import me.ahoo.wow.modeling.DefaultAggregateId;
import me.ahoo.wow.modeling.MaterializedNamedAggregate;
import me.ahoo.wow.tck.event.MockDomainEventStreams;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StateEventDataSourceCompatibilityTest {
    @Test
    void existingSixArgumentConstructionAndCopyRemainAvailable() {
        var aggregateId = new DefaultAggregateId(new MaterializedNamedAggregate("test", "aggregate"), "id", "tenant");
        DomainEventStream stream = MockDomainEventStreams.INSTANCE.generateEventStream(aggregateId);
        var original = new StateEventData<>(stream, "state", "first", 1L, Map.of(), false);
        assertEquals("copy", original.copy(stream, "copy", "first", 1L, Map.of(), false).getState());

        var transferred = new StateEventData<>(stream, "state", "first", 1L, Map.of(), false, "new-owner", "new-space");
        var copied = transferred.copy(stream, "copy", "first", 1L, Map.of(), false);
        assertEquals("new-owner", copied.getOwnerId());
        assertEquals("new-space", copied.getSpaceId());
    }
}

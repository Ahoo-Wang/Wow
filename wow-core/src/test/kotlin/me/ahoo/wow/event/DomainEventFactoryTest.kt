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

package me.ahoo.wow.event

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.event.DEFAULT_EVENT_SEQUENCE
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.SpaceIdCapable
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.messaging.DefaultHeader
import org.junit.jupiter.api.Test

class DomainEventFactoryTest {

    @Test
    fun `an event that names its aggregate takes the aggregate from its body`() {
        val header = DefaultHeader.empty().with("k", "v")

        val event = FixtureRoutedEvent(id = "routed-1").toDomainEvent(
            aggregateId = "aggregate-1",
            tenantId = "tenant-1",
            commandId = "command-1",
            ownerId = "owner-1",
            spaceId = "space-1",
            id = "event-1",
            version = 4,
            sequence = 2,
            isLast = false,
            header = header,
            createTime = 42,
        )

        event.aggregateId.contextName.assert().isEqualTo("event")
        event.aggregateId.aggregateName.assert().isEqualTo("fixture")
        event.aggregateId.id.assert().isEqualTo("aggregate-1")
        event.aggregateId.tenantId.assert().isEqualTo("tenant-1")
        event.id.assert().isEqualTo("event-1")
        event.commandId.assert().isEqualTo("command-1")
        event.ownerId.assert().isEqualTo("owner-1")
        event.spaceId.assert().isEqualTo("space-1")
        event.version.assert().isEqualTo(4)
        event.sequence.assert().isEqualTo(2)
        event.isLast.assert().isFalse()
        event.header.assert().isSameAs(header)
        event.createTime.assert().isEqualTo(42)
        event.body.assert().isEqualTo(FixtureRoutedEvent(id = "routed-1"))
    }

    @Test
    fun `the defaults are the first event of a new stream`() {
        val before = System.currentTimeMillis()

        val event = FixtureRoutedEvent(id = "routed-1").toDomainEvent(
            aggregateId = "aggregate-1",
            tenantId = TenantId.DEFAULT_TENANT_ID,
            commandId = "command-1",
        )

        event.ownerId.assert().isEqualTo(OwnerId.DEFAULT_OWNER_ID)
        event.spaceId.assert().isEqualTo(SpaceIdCapable.DEFAULT_SPACE_ID)
        event.version.assert().isEqualTo(Version.INITIAL_VERSION)
        event.sequence.assert().isEqualTo(DEFAULT_EVENT_SEQUENCE)
        event.isLast.assert().isTrue()
        event.header.isEmpty().assert().isTrue()
        event.id.assert().isNotBlank()
        event.createTime.assert().isGreaterThanOrEqualTo(before)
    }
}

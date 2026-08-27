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

package me.ahoo.wow.configuration

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AggregateConfigurationTest {

    @Test
    fun `merge should keep first non blank scalar values and union scopes commands and events`() {
        val current = Aggregate(
            scopes = linkedSetOf("me.ahoo.order"),
            type = FixtureAggregate::class.java.name,
            tenantId = "tenant-a",
            id = "order-id",
            commands = linkedSetOf("me.ahoo.order.command"),
            events = linkedSetOf("me.ahoo.order.event"),
        )
        val other = Aggregate(
            scopes = linkedSetOf("me.ahoo.order.extra"),
            type = FixtureAggregate::class.java.name,
            tenantId = "tenant-b",
            id = "other-id",
            commands = linkedSetOf("me.ahoo.order.command.extra"),
            events = linkedSetOf("me.ahoo.order.event.extra"),
        )

        val merged = current.merge(other)

        merged.type.assert().isEqualTo(FixtureAggregate::class.java.name)
        merged.tenantId.assert().isEqualTo("tenant-a")
        merged.id.assert().isEqualTo("order-id")
        merged.scopes.assert().isEqualTo(linkedSetOf("me.ahoo.order", "me.ahoo.order.extra"))
        merged.commands.assert().isEqualTo(linkedSetOf("me.ahoo.order.command", "me.ahoo.order.command.extra"))
        merged.events.assert().isEqualTo(linkedSetOf("me.ahoo.order.event", "me.ahoo.order.event.extra"))
    }

    @Test
    fun `merge should reject conflicting aggregate types when both are present`() {
        val exception = assertThrows<IllegalStateException> {
            Aggregate(type = FixtureAggregate::class.java.name)
                .merge(Aggregate(type = OtherFixtureAggregate::class.java.name))
        }

        exception.message.assert().contains("conflicts")
    }

    private class FixtureAggregate
    private class OtherFixtureAggregate
}

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

package me.ahoo.wow.schema

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.schema.Types.isStdType
import me.ahoo.wow.schema.Types.isWowType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TypesTest {
    @Test
    fun `isWowType should return true for AggregateId`() {
        assertTrue(AggregateId::class.java.isWowType())
    }

    @Test
    fun `isWowType should return true for CommandMessage`() {
        assertTrue(CommandMessage::class.java.isWowType())
    }

    @Test
    fun `isWowType should return true for DomainEvent`() {
        assertTrue(DomainEvent::class.java.isWowType())
    }

    @Test
    fun `isWowType should return true for DomainEventStream`() {
        assertTrue(DomainEventStream::class.java.isWowType())
    }

    @Test
    fun `isWowType should return true for Snapshot`() {
        assertTrue(Snapshot::class.java.isWowType())
    }

    @Test
    fun `isWowType should return true for StateAggregate`() {
        assertTrue(StateAggregate::class.java.isWowType())
    }

    @Test
    fun `isWowType should return true for StateEvent`() {
        assertTrue(StateEvent::class.java.isWowType())
    }

    @Test
    fun `isWowType should return false for String`() {
        assertFalse(String::class.java.isWowType())
    }

    @Test
    fun `isWowType should return false for Int`() {
        assertFalse(Int::class.java.isWowType())
    }

    @Test
    fun `isWowType should return false for List`() {
        assertFalse(List::class.java.isWowType())
    }

    @Test
    fun `isStdType should return true for String`() {
        assertTrue(String::class.java.isStdType())
    }

    @Test
    fun `isStdType should return true for Int`() {
        assertTrue(Int::class.java.isStdType())
    }

    @Test
    fun `isStdType should return true for List`() {
        assertTrue(List::class.java.isStdType())
    }

    @Test
    fun `isStdType should return false for AggregateId`() {
        assertFalse(AggregateId::class.java.isStdType())
    }
}

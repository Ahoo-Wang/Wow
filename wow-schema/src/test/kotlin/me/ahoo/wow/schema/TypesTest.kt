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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.schema.Types.isStdType
import me.ahoo.wow.schema.Types.isWowType
import org.junit.jupiter.api.Test

class TypesTest {
    @Test
    fun `isWowType should return true for AggregateId`() {
        AggregateId::class.java.isWowType().assert().isTrue()
    }

    @Test
    fun `isWowType should return true for CommandMessage`() {
        CommandMessage::class.java.isWowType().assert().isTrue()
    }

    @Test
    fun `isWowType should return true for DomainEvent`() {
        DomainEvent::class.java.isWowType().assert().isTrue()
    }

    @Test
    fun `isWowType should return true for DomainEventStream`() {
        DomainEventStream::class.java.isWowType().assert().isTrue()
    }

    @Test
    fun `isWowType should return true for Snapshot`() {
        Snapshot::class.java.isWowType().assert().isTrue()
    }

    @Test
    fun `isWowType should return true for StateAggregate`() {
        StateAggregate::class.java.isWowType().assert().isTrue()
    }

    @Test
    fun `isWowType should return true for StateEvent`() {
        StateEvent::class.java.isWowType().assert().isTrue()
    }

    @Test
    fun `isWowType should return false for String`() {
        String::class.java.isWowType().assert().isFalse()
    }

    @Test
    fun `isWowType should return false for Int`() {
        Int::class.java.isWowType().assert().isFalse()
    }

    @Test
    fun `isWowType should return false for List`() {
        List::class.java.isWowType().assert().isFalse()
    }

    @Test
    fun `isStdType should return true for String`() {
        String::class.java.isStdType().assert().isTrue()
    }

    @Test
    fun `isStdType should return true for Int`() {
        Int::class.java.isStdType().assert().isTrue()
    }

    @Test
    fun `isStdType should return true for List`() {
        List::class.java.isStdType().assert().isTrue()
    }

    @Test
    fun `isStdType should return false for AggregateId`() {
        AggregateId::class.java.isStdType().assert().isFalse()
    }
}

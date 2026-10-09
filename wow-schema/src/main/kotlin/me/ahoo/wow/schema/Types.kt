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
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.modeling.state.StateAggregate
import java.lang.reflect.AnnotatedElement

object Types {
    internal fun AnnotatedElement.isKotlinElement(): Boolean {
        return getAnnotation(Metadata::class.java) != null
    }

    private val WOW_TYPES = listOf(
        AggregateId::class.java,
        CommandMessage::class.java,
        DomainEvent::class.java,
        DomainEventStream::class.java,
        Snapshot::class.java,
        StateAggregate::class.java,
        StateEvent::class.java,
    )

    /** Framework types whose schema comes from a bundled definition rather than Kotlin reflection. */
    internal fun Class<*>.isWowType(): Boolean =
        this == FilterExpression::class.java || WOW_TYPES.any { it.isAssignableFrom(this) }

    private val STD_PACKAGE_PREFIXES = listOf("java.", "javax.", "kotlin.", "kotlinx.")

    /** Arrays, primitives, enums and JDK/Kotlin library types, which never get a Wow schema name. */
    fun Class<*>.isStdType(): Boolean =
        isArray || isPrimitive || isEnum || STD_PACKAGE_PREFIXES.any(name::startsWith)
}

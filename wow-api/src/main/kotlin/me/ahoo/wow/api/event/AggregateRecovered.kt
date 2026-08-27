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

package me.ahoo.wow.api.event

import me.ahoo.wow.api.annotation.Event

/**
 * Marker interface for events indicating that a previously deleted aggregate has been recovered.
 *
 * This interface is implemented by domain events that signal the restoration of an
 * aggregate that was previously deleted. Aggregate recovery allows systems to undo
 * deletion operations, restoring the aggregate to its previous state and allowing
 * normal operations to resume.
 *
 * Recovery events are typically used in scenarios where:
 * - Deletion was performed in error
 * - Regulatory requirements demand data retention
 * - Business processes require the ability to "undelete" entities
 * - Data needs to be restored from backups or archives
 *
 * sample usage:
 * ```kotlin
 * class OrderRecovered : AggregateRecovered {
 *     // Additional recovery metadata can be included here
 *     val recoveredAt: Instant = Instant.now()
 *     val recoveredBy: String = currentUser()
 * }
 * ```
 * @see AggregateDeleted for events indicating aggregate deletion
 * @see me.ahoo.wow.api.annotation.Event for the event annotation
 *
 */
@Event
interface AggregateRecovered

/**
 * Default implementation of [AggregateRecovered] for simple recovery events.
 *
 * This object can be used directly when no additional event data is needed
 * beyond the fact that an aggregate has been recovered. It serves as a
 * convenient default for basic aggregate recovery scenarios.
 */
object DefaultAggregateRecovered : AggregateRecovered

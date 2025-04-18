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
 * Represents an event that is triggered when an aggregate is deleted.
 *
 * This event can be used to notify other components or services in the system that a specific aggregate has been deleted,
 * allowing them to take any necessary actions, such as updating their state or performing cleanup operations.
 */
@Event
interface AggregateDeleted

object DefaultAggregateDeleted : AggregateDeleted

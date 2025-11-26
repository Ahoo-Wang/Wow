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

import me.ahoo.wow.api.Version
import me.ahoo.wow.api.command.CommandId
import me.ahoo.wow.api.messaging.NamedBoundedContextMessage
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.OwnerId

interface EventMessage<SOURCE : EventMessage<SOURCE, T>, out T> :
    NamedBoundedContextMessage<SOURCE, T>,
    CommandId,
    NamedAggregate,
    Version,
    AggregateIdCapable,
    OwnerId

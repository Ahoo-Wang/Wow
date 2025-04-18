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

package me.ahoo.wow.api.command

import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Summary

/**
 * Represents a command to delete an aggregate. This interface is part of the command handling mechanism, ensuring that the deletion of an aggregate can be managed and tracked.
 *
 * Implementations of this interface are expected to provide the necessary logic to delete an aggregate, which may include removing the aggregate from the system, updating related entities
 * , and ensuring that the operation is idempotent.
 */
interface DeleteAggregate

@Summary("Delete aggregate")
@CommandRoute(action = "", method = CommandRoute.Method.DELETE, appendIdPath = CommandRoute.AppendPath.ALWAYS)
object DefaultDeleteAggregate : DeleteAggregate

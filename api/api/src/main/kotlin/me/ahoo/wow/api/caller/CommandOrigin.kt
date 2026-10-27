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

package me.ahoo.wow.api.caller

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * What a command of a caller records of it ([Caller.originOf]): the [scope] it acts in, the [operator] that sends it
 * and the request [facts] it carries, the calling application's among them. The command gateway, the edge, the client
 * and the test DSL derive them alike from the caller.
 */
@InternalWowApi
class CommandOrigin @InternalWowApi constructor(
    val scope: Scope,
    val operator: Operator?,
    val facts: Map<String, String>,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is CommandOrigin && scope == other.scope && operator == other.operator &&
            facts == other.facts

    override fun hashCode(): Int = listOf(scope, operator, facts).hashCode()

    override fun toString(): String = "CommandOrigin(scope=$scope, operator=$operator, facts=$facts)"
}

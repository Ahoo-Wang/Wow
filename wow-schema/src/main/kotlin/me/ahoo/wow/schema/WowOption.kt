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

enum class WowOption {
    /**
     *
     * @see me.ahoo.wow.api.annotation.CommandRoute.PathVariable
     * @see me.ahoo.wow.api.annotation.CommandRoute.HeaderVariable
     */
    IGNORE_COMMAND_ROUTE_VARIABLE,
    KOTLIN,
    WOW_NAMING_STRATEGY;

    companion object {
        val ALL: Set<WowOption> = setOf(IGNORE_COMMAND_ROUTE_VARIABLE, KOTLIN, WOW_NAMING_STRATEGY)
    }
}

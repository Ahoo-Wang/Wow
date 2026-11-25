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

package me.ahoo.wow.architecture.fixture

/** A type the fixture dump leaves out, as one an `@InternalWowApi` mark leaves out of a real dump. */
class Hidden

/** A type the fixture dump lists. */
class Shown

/** Members whose JVM signatures erase the type they name: a suspend function's result, a generic argument. */
class Exposed {
    suspend fun hiddenLater(): Hidden = Hidden()

    fun hiddenList(): List<Hidden> = emptyList()

    fun shownList(): List<Shown> = emptyList()

    fun hiddenButUnlisted(): Map<String, Hidden> = emptyMap()
}

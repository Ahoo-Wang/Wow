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

package me.ahoo.wow.api.aggregate

/**
 * How a command relates to its aggregate's existence, as `@CreateAggregate` and `@AllowCreate` declare it. The
 * Manifest states it in its own vocabulary (`Creation` of `wow-manifest`), which a test of that module keeps in step.
 */
enum class Creation {
    /** The aggregate must exist. */
    NONE,

    /** The command creates the aggregate: it must not exist yet. */
    REQUIRED,

    /** The command creates the aggregate when it does not exist, and acts on it when it does. */
    ALLOWED,
}

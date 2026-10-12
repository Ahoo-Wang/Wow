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

package me.ahoo.wow.api.decide

/**
 * What a decide function (or an after hook) can ask of the kernel besides returning its decision.
 *
 * A decide function may act on other systems, such as reserving a unique key. [onAppendFailure] undoes such an act
 * when the command's events are surely not appended: the decision rejects, the version conflicts and the command is
 * decided again, or deciding fails. It does not run when whether the append committed is unknown, and it cannot run
 * if the process dies; an act that must not leak then needs its own expiry.
 */
interface DecideScope {
    /** The command being decided, with what admission learned about its request. */
    val command: Command<*>

    /** Adds [value] under [key] to what the caller is answered once the events are appended. */
    fun putResult(key: String, value: Any)

    /** Registers [compensation] to run when this attempt's events are surely not appended. */
    fun onAppendFailure(compensation: suspend () -> Unit)

    /** Registers [action] to run once this attempt's events are appended. A failure of it is logged, not raised. */
    fun afterAppend(action: suspend () -> Unit)

    /**
     * Reserves [key] in [namespace] for this command's aggregate, such as a unique user name (decision D38); false
     * when another aggregate holds it. The reservation is pending, with an expiry, until the events are appended; then
     * it is confirmed. When they are surely not appended it is released, and if the process dies first it expires
     * (finding R8). A key the aggregate already holds confirmed stays held whatever this attempt does. Releasing or
     * moving a confirmed key, as a rename does, is a saga's work.
     */
    suspend fun reserve(namespace: String, key: String): Boolean
}

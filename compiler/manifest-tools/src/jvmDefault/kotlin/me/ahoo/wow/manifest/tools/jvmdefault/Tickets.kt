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

package me.ahoo.wow.manifest.tools.jvmdefault

/** A value's identity, of the type its implementation says. */
interface Identified<ID> {
    val id: ID
}

/** A ticket's identity, which its interface computes from its code. */
interface Numbered : Identified<String> {
    val code: String

    override val id: String get() = "t-$code"
}

enum class Stage { OPEN, CLOSED }

/** A state's stage, hold and note, which its interface computes. */
interface Staged {
    val lines: Int

    val stage: Stage get() = if (lines > 0) Stage.OPEN else Stage.CLOSED

    val held: Boolean get() = false

    val note: String? get() = null
}

/** A ticket, whose interface computes its identity, and the class its label. */
data class Ticket(override val code: String) : Numbered {
    val label: String get() = "#$code"
}

/** A state whose interface computes most of its computed properties, and the class one of its own. */
class TicketState(val id: String) : Staged {
    override var lines: Int = 0
    var ticket: Ticket = Ticket("a")
    val empty: Boolean get() = lines == 0
}

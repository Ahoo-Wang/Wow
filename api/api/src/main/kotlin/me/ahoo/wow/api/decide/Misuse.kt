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

import me.ahoo.wow.api.annotation.InternalWowApi
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * A Wow API that cannot serve a call as the code made it: the code's own mistake, a wrong argument (a read of a state
 * class no aggregate has, a command to a context no peer describes) or a wrong state of use (a command object to a peer
 * that takes named commands alone, a peer the configuration gives no address), never the caller's, nor a peer's or the
 * wire's, which are transport failures. It is a subclass of [IllegalArgumentException] or
 * [IllegalStateException], never the class itself, which a decide function's own `require` and `check` throw and the
 * kernel answers as the command's rejection: a decide function that runs into a [Misuse] fails its command, as an
 * unexpected exception does, and a subscription handler's is unrecoverable, since it fails the same way every time. The
 * kernel's decide scope, the read API and the command clients throw it.
 */
@InternalWowApi
sealed interface Misuse {
    /** An argument the API cannot serve: its `require`. */
    @InternalWowApi
    class Argument(message: String) : IllegalArgumentException(message), Misuse

    /** A call the API cannot serve in its state: its `check`. */
    @InternalWowApi
    class State(message: String) : IllegalStateException(message), Misuse
}

/** Throws a [Misuse.Argument] of [message] unless [value] holds: `require` for a Wow API's own arguments. */
@InternalWowApi
@OptIn(ExperimentalContracts::class)
inline fun requireUse(value: Boolean, message: () -> String) {
    contract { returns() implies value }
    if (!value) throw Misuse.Argument(message())
}

/** Throws a [Misuse.State] of [message] unless [value] holds: `check` for a Wow API's own state. */
@InternalWowApi
@OptIn(ExperimentalContracts::class)
inline fun checkUse(value: Boolean, message: () -> String) {
    contract { returns() implies value }
    if (!value) throw Misuse.State(message())
}

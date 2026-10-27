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

package me.ahoo.wow.runtime.admission

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.wire.CallWire
import me.ahoo.wow.spi.wait.toContext
import me.ahoo.wow.spi.wait.toWire
import me.ahoo.wow.spi.wire.CallWires

/**
 * A [CallContext] as the `Wow-Call` header: its [CallWire], as `:spi`'s [CallWires] encodes it. A fact
 * that does not propagate is dropped as it is parsed ([CallContext.facts]).
 */
object CallHeaders {
    /** [call] as the header's value. */
    @JvmStatic
    fun format(call: CallContext): String = CallWires.encode(call.toWire())

    /** The call [value] spells; an [IllegalArgumentException] when it does not parse. */
    @JvmStatic
    fun parse(value: String): CallContext = CallWires.decode(value).toContext()

    private fun CallContext.toWire(): CallWire = CallWire(
        commandId = commandId,
        tenantId = tenantId,
        ownerId = scope.ownerId,
        spaceId = scope.spaceId,
        causation = causation,
        operator = operator,
        wait = wait?.toWire(),
        facts = facts,
    )

    private fun CallWire.toContext(): CallContext = CallContext(
        commandId = commandId,
        tenantId = tenantId,
        scope = Scope(ownerId, spaceId),
        causation = causation,
        operator = operator,
        wait = wait?.toContext(),
        facts = facts,
    )
}

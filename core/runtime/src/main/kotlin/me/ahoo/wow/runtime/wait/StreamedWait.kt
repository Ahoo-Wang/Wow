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

package me.ahoo.wow.runtime.wait

import me.ahoo.wow.api.annotation.InternalWowApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A command's wait answered as an event stream, in the coroutine context its results are collected in (#6817): the
 * edge sends the stream's head once the command is accepted, and every refusal before that is still a problem with its
 * own status.
 *
 * [WaitCoordinator.stream] and [WaitCoordinator.relay] call [accepted] once the command is accepted, just before its
 * ACCEPTED result is offered, whether its plan names that hop or not; a command refused before (a full node, an invalid
 * plan, a command topic that did not take it) never calls it. With it, a wait whose execution throws after acceptance
 * ends with a FAILED result at COMMITTED instead of failing the flow, since the head is sent:
 * `wow.storage.unavailable` for a store that is unavailable, `wow.command.unknown` otherwise. Its detail names neither
 * the failure nor the store, which the node's log does. A fatal error ([me.ahoo.wow.runtime.health.NodeFatals.isFatal])
 * still fails the flow.
 *
 * With it too, a command of this node is settled apart from its results, each emitted as its hop is reached, so a plan
 * that names ACCEPTED streams it at once. A wait without one, a JSON answer's, settles its command first, as before.
 */
@InternalWowApi
class StreamedWait(val accepted: () -> Unit) : AbstractCoroutineContextElement(Key) {
    @InternalWowApi
    companion object Key : CoroutineContext.Key<StreamedWait>
}

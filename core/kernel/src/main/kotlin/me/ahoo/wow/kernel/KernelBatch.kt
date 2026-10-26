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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.spi.wait.WaitContext

/** A command an aggregate's lane hands to [Kernel.executeBatch], with the wait context the runtime built for it. */
@InternalWowApi
class QueuedCommand(val command: Command<*>, val wait: WaitContext? = null)

/** What [Kernel.executeBatch] did with one command of a batch. */
@InternalWowApi
sealed interface BatchOutcome {
    /** The command's outcome, as [Kernel.execute] would have answered it. */
    @InternalWowApi
    class Done(val outcome: CommandOutcome) : BatchOutcome

    /** The command failed with [failure], as [Kernel.execute] would have thrown it. */
    @InternalWowApi
    class Failed(val failure: Throwable) : BatchOutcome

    /**
     * Not executed: a stream before it in the batch did not commit, or it could not join the batch. The lane runs it
     * again, in order.
     */
    @InternalWowApi
    data object Again : BatchOutcome
}

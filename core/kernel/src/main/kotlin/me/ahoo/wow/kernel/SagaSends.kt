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

/**
 * What a saga's handler declares it sends ([me.ahoo.wow.api.annotation.Sends]), by the commands' classes: those it
 * [derives] (returns), those it [schedules] with `SagaContext.schedule` and those it [calls] with `SagaContext.call`.
 * The compiler writes them into the handler's binding: what `@Sends` declares, else, for [derives], what the handler's
 * return type names. A command of a class none of the list's classes is, or is a subclass of, is undeclared: sending it
 * fails the handling with an [UndeclaredSendException].
 */
@InternalWowApi
class SagaSends(
    val derives: List<Class<*>> = emptyList(),
    val schedules: List<Class<*>> = emptyList(),
    val calls: List<Class<*>> = emptyList(),
) {
    /** Refuses [command], which [subscription] derives, unless it [derives] it. */
    fun checkDerives(subscription: String, command: Class<*>) = check(subscription, command, derives, DERIVES)

    /** Refuses [command], which [subscription] schedules, unless it [schedules] it. */
    fun checkSchedules(subscription: String, command: Class<*>) = check(subscription, command, schedules, SCHEDULES)

    /** Refuses [command], which [subscription] calls, unless it [calls] it. */
    fun checkCalls(subscription: String, command: Class<*>) = check(subscription, command, calls, CALLS)

    private fun check(subscription: String, command: Class<*>, declared: List<Class<*>>, member: String) {
        if (declared.none { it.isAssignableFrom(command) }) {
            throw UndeclaredSendException(subscription, command, member)
        }
    }

    override fun toString(): String = "SagaSends(derives=$derives, schedules=$schedules, calls=$calls)"

    @InternalWowApi
    companion object {
        private const val DERIVES = "derives"
        private const val SCHEDULES = "schedules"
        private const val CALLS = "calls"
    }
}

/**
 * A saga's handler [subscription] sent [command], a command of a class its `@Sends([member])` does not declare: a bug in
 * the saga, not an outcome of the command, which no retry changes. The handling fails unrecoverable, so the aggregate
 * parks at once, and the command is not sent.
 */
class UndeclaredSendException(
    val subscription: String,
    val command: Class<*>,
    val member: String,
) : IllegalStateException(
    // Each member is the verb of its send: derives, schedules, calls.
    "$subscription $member ${command.name}, which its @Sends($member) does not declare: add " +
        "${command.simpleName}::class to it, so the Manifest lists it.",
)

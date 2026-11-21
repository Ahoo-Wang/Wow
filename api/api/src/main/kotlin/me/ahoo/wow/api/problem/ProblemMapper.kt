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

package me.ahoo.wow.api.problem

/**
 * How the application answers a failure of its own that the framework does not know (9.x `ErrorInfoConverterFactory`):
 * a failed call to another system, say, answered with a code of the application rather than a 500 that says nothing.
 * The host takes the application's mappers as beans, in their order, and a shared library contributes its own the
 * same way.
 *
 * A mapper is asked only where a public route would otherwise answer a 500 that says nothing of its cause (a query's
 * `wow.query.failed` among them): on the application's own routes, a service route's handler, Wow's state reads and
 * queries, a streamed list that fails after its first record. It never sees what the framework answers itself (a
 * [WowProblemException], a command's rejection, an authentication or admission refusal, a store that is unavailable),
 * nor a command's failure, which is the command's result (`wow.command.failed`): a decide function that calls another
 * system and fails rejects the command itself.
 *
 * The framework asks the mappers, in order, for the failure and then each of its causes, and the first problem one
 * gives is the answer. That problem is checked: a code the framework reserves (`wow.*`), a status outside 4xx and 5xx
 * or 401 (only authentication answers it), or a command's [result][WowProblemException.result] is not taken, and the
 * failure is answered as no mapper's. A mapper that throws is taken as one that does not know the failure. The
 * problem's message is its `detail`, which the caller reads.
 *
 * A mapper is called on the failure's path only, after the request failed: it decides from the exception alone,
 * quickly and without blocking.
 */
fun interface ProblemMapper {
    /** The problem [failure] is answered with, or null when it is not this mapper's. */
    fun problemOf(failure: Exception): WowProblemException?
}

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

package me.ahoo.wow.api.subscribe

/**
 * A handler's failure that no retry changes, which the handler says itself: it throws this (or a subclass of it), and
 * the subscription parks the aggregate at once, without a retry, marked for an operator to look at; a wait at the
 * handler's hop ends FAILED. A reactor that finds the buyer unknown to its notification service, say: asking again
 * will not change that. Thrown as the cause of another failure, it parks too.
 *
 * It is no business outcome: a rejection, which a saga compensates, is a command's (`@OnRejected`). A handler's own
 * `@Retry(recoverable = [...])` that lists its class takes precedence, as for any failure it classifies itself.
 */
open class UnrecoverableHandlingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

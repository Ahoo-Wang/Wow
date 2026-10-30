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

package me.ahoo.wow.api.annotation

import kotlin.reflect.KClass

/**
 * What a [Saga]'s handler sends, by the commands' classes: any of its handlers, `@OnEvent`, `@OnRejected`,
 * `@OnCommitted` or `@OnSchedule`, may carry it, and nothing else does: the compiler refuses it on a [Projection]'s or
 * a [Reactor]'s handler, or on a function that is no handler. Each class is a command class of this context or of
 * another (from that context's api), or a sealed type, which stands for its concrete subclasses; the service's build
 * fails on a class that neither this context nor a peer contract on its class path decides. The compiler writes them
 * into the Manifest, and the runtime holds the handler to them: sending a command of a class it does not declare fails
 * the handling, which no retry changes (unrecoverable), so the aggregate parks at once.
 *
 * @param derives the commands the handler returns. Without it, the handler's return type names them
 *   (`CommandRequest<X>`, an `Iterable`, a `Sequence` or a `Flow` of them, the subclasses of a sealed `X`); a handler
 *   whose return type names none (`CommandRequest<*>`, `CommandRequest<Any>`) must declare them here, or it does not
 *   compile. A wait chain may follow the saga's hop only into the contexts of these commands. A handler that returns
 *   `Unit` derives none and declares none.
 * @param schedules the commands the handler schedules with `SagaContext.schedule`, which the Manifest lists as the
 *   subscription's `schedules`.
 * @param calls the commands the handler sends to another context with `SagaContext.call`: the
 *   compiler lists them as the subscription's `calls` in the Manifest, which a wait chain follows as it follows
 *   [derives], and the node does not build when a called context is its own, is unknown, or is one its command caller
 *   says it does not reach (`wow.peers.<context>.url`). A called command needs no command topic, and is not one the
 *   handler derives. A command of the handler's own context is derived, never called.
 */
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class Sends(
    val derives: Array<KClass<*>> = [],
    val schedules: Array<KClass<*>> = [],
    val calls: Array<KClass<*>> = [],
)

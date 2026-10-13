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

import java.lang.annotation.Inherited

/**
 * A handler of a [Saga] for the rejection of a command the saga derived (V10, decisions D136, D153): it takes a
 * [me.ahoo.wow.api.subscribe.CommandRejected] of the command's type (or of `*`, for every command the saga derives)
 * and, optionally, the `SagaContext`, and returns the commands that compensate, as an [OnEvent] handler of a saga
 * does. A rejection is a business outcome: the runtime hands it here instead of parking the aggregate, whether the
 * command was rejected in the saga's own context or in another, whose receipt comes back (design 04 §5). Each handler
 * is its own subscription, named `processor.function`.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class OnRejected

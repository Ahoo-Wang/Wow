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
 * A handler of a [Saga] for the success receipt of a command the saga derived, the counterpart
 * of [OnRejected]: it takes a [me.ahoo.wow.api.subscribe.CommandReceipt.Committed] of the command's type (or of `*`, for every
 * command the saga derives) and, optionally, the `SagaContext`, and returns the commands it derives in turn, as an
 * [OnEvent] handler of a saga does.
 *
 * Only the commands it takes ask for a receipt: the compiler writes the handler into the Manifest, and a command the
 * saga sends to another context carries the request in its `replyTo`; the target sends the receipt once the command
 * is committed, before it moves past the command. A command of the saga's own context is committed
 * within the saga's handling, which hands its receipt here right after. The receipt is not a hop of a wait chain:
 * `Wow-Wait` follows the derived command as before.
 *
 * A receipt of the saga's own context is handled inside the handling of the `@OnEvent` handler that derived the
 * command: under that handler's (or its processor's) `@Retry` budget, parked under that handler's subscription, and a
 * retry runs that handler again, whose deterministic ids make it harmless. A receipt from another context is handled in
 * the context's receipts subscription, `wow.receipts.<context>`, under the host's retry for that name. It takes no
 * `@Retry`: its budget never depends on where its command went.
 *
 * A handler of both receipts of a command is annotated [OnRejected] too and takes a
 * [me.ahoo.wow.api.subscribe.CommandReceipt] of the command's type; each annotation declares its own path.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class OnCommitted

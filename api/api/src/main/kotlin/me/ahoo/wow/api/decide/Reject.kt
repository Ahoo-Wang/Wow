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

@file:JvmName("Rejects")

package me.ahoo.wow.api.decide

/**
 * Refuses the command being decided with the rejection [code], [message] and structured [extensions]: it throws a
 * [RejectedException], which the kernel answers as that rejection, so nothing is appended. [message] is shown to the
 * caller. The way a decide function that returns its event type rejects, with or without a [DecideScope];
 * `require` (`wow.command.invalid`) and `check` (`wow.command.refused`) reject a broken precondition alike. It is
 * inline like `error()`, so the rejecting line is the caller's.
 */
@Suppress("NOTHING_TO_INLINE")
inline fun reject(code: String, message: String, extensions: Map<String, Any?> = emptyMap()): Nothing =
    throw RejectedException(Rejection.of(code, message, extensions))

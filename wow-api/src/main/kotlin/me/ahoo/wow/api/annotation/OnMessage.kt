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

import me.ahoo.wow.api.messaging.function.FunctionKind
import java.lang.annotation.Inherited

/**
 * Base annotation for message handler functions.
 *
 * This annotation serves as a foundation for specific message handler annotations
 * like @OnCommand, @OnEvent, and @OnSourcing. It provides common configuration
 * for message processing functions.
 *
 * @param functionKind The type of message this handler processes (COMMAND, EVENT, etc.).
 * @param defaultFunctionName The default naming convention for handler functions.
 *
 * @see OnCommand for command handlers
 * @see OnEvent for event handlers
 * @see OnSourcing for state sourcing handlers
 * @see FunctionKind for available message types
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class OnMessage(
    val functionKind: FunctionKind,
    val defaultFunctionName: String
)

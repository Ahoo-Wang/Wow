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

package me.ahoo.wow.compiler

import me.ahoo.wow.naming.PascalCaseStrategy
import me.ahoo.wow.naming.SnakeCaseStrategy

/**
 * `PascalCase` to `snake_case`, with the same strategies as the runtime's `NamingConverter.PASCAL_TO_SNAKE`, so a
 * name the compiler writes into `wow-metadata.json` is the name the runtime derives.
 */
internal fun String.pascalToSnake(): String = SnakeCaseStrategy.transform(PascalCaseStrategy.segment(this))

/** The runtime's `me.ahoo.wow.messaging.handler.MessageExchange`, named without depending on wow-core. */
internal const val MESSAGE_EXCHANGE_NAME = "me.ahoo.wow.messaging.handler.MessageExchange"

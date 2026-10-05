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

package me.ahoo.wow.modeling.annotation

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.messaging.function.FunctionAccessorMetadata

private val log = KotlinLogging.logger {}

/**
 * Registers [function] for its supported type unless a function already handles that type. The first one found keeps
 * the type, as it always has; a later one is ignored with a warning (V7), since only one of them can ever run.
 */
internal fun <P, R> MutableMap<Class<*>, FunctionAccessorMetadata<P, R>>.registerFirst(
    function: FunctionAccessorMetadata<P, R>,
    kind: String
) {
    val registered = putIfAbsent(function.supportedType, function) ?: return
    log.warn {
        "Duplicate $kind functions for [${function.supportedType.name}] in [${function.processorType.name}]: " +
            "[${registered.name}] handles it, [${function.name}] is ignored."
    }
}

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

package me.ahoo.wow.spi.config

import me.ahoo.wow.api.annotation.InternalWowApi
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * A configuration Wow cannot start with: [key] is the configuration key at fault, [value] its value as configured
 * (null when it is not set, or when the fault is between keys), [reason] what is wrong and [fix] what to change. The
 * core modules that enforce a rule of the configuration throw it where the rule lives (the internal port's security
 * floor of `wow-http`, the query bulkheads of `wow-query`), and a host reports it as it reports its own:
 * the Spring Boot host through a failure analyzer, instead of a stack of bean creation failures.
 */
class WowConfigurationException(
    val key: String,
    val value: Any?,
    val reason: String,
    val fix: String,
) : IllegalStateException("$reason $fix")

/** Throws a [WowConfigurationException] of [key] with [value] and [fix] when [condition] is false. */
@InternalWowApi
@OptIn(ExperimentalContracts::class)
inline fun configCheck(condition: Boolean, key: String, value: Any?, fix: String, reason: () -> String) {
    contract { returns() implies condition }
    if (!condition) {
        throw WowConfigurationException(key, value, reason(), fix)
    }
}

/**
 * [value], or, when it is null, a [WowConfigurationException] of [key] with [fix]; [configured] is the key's value, null
 * when it is not set.
 */
@InternalWowApi
inline fun <T : Any> configNotNull(
    value: T?,
    key: String,
    fix: String,
    configured: Any? = null,
    reason: () -> String,
): T = value ?: throw WowConfigurationException(key, configured, reason(), fix)

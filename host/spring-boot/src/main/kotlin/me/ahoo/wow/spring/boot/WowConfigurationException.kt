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

package me.ahoo.wow.spring.boot

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * A configuration the host cannot start with: [key] is the configuration key at fault, [value] its value as
 * configured (null when it is not set, or when the fault is between keys), [reason] what is wrong and [fix] what to
 * change. Spring Boot reports it through [WowConfigurationFailureAnalyzer], instead of a stack of bean creation
 * failures.
 */
class WowConfigurationException(
    val key: String,
    val value: Any?,
    val reason: String,
    val fix: String,
) : IllegalStateException("$reason $fix")

/** Reports a [WowConfigurationException] at startup: the key, its value, what is wrong, and what to change. */
internal class WowConfigurationFailureAnalyzer : AbstractFailureAnalyzer<WowConfigurationException>() {
    override fun analyze(rootFailure: Throwable, cause: WowConfigurationException): FailureAnalysis =
        FailureAnalysis(
            buildString {
                appendLine("Wow's configuration is invalid: ${cause.reason}")
                appendLine()
                append("    Property: ${cause.key}")
                cause.value?.let { append("\n    Value: $it") }
            },
            cause.fix,
            cause,
        )
}

/** The fix of a `wow.aggregates.[<key>]` that names no aggregate type. */
internal const val AGGREGATE_KEY_FIX: String = "Name an aggregate type as <context>.<aggregate>."

/** Throws a [WowConfigurationException] of [key] with [value] and [fix] when [condition] is false. */
@OptIn(ExperimentalContracts::class)
internal inline fun configCheck(condition: Boolean, key: String, value: Any?, fix: String, reason: () -> String) {
    contract { returns() implies condition }
    if (!condition) {
        throw WowConfigurationException(key, value, reason(), fix)
    }
}

/**
 * [value], or, when it is null, a [WowConfigurationException] of [key] with [fix]; [configured] is the key's value, null
 * when it is not set.
 */
internal inline fun <T : Any> configNotNull(
    value: T?,
    key: String,
    fix: String,
    configured: Any? = null,
    reason: () -> String,
): T = value ?: throw WowConfigurationException(key, configured, reason(), fix)

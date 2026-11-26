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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spring.boot.transport.PortProviders
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Every numeric or duration key of the host's ([DocumentedKeys]), set to a value it cannot take (negative, and zero
 * where its description gives zero no meaning), is accepted by the start or refused as a [WowConfigurationException]
 * naming that key or a group above it, never another failure and never another key: a key added later keeps its name
 * in the refusal without a test of its own. The keys are set all together, and each one a refusal names is taken out
 * until the host starts with the rest; a group named above several keys set is tried key by key.
 *
 * A provider's keys are read where its store, transport or Sink opens, against its server; the host wraps that opening
 * in [PortProviders.configured], which names the provider's group, and each backend's tests hold the refusals of its
 * options. An entry the test host cannot serve is left out: `wow.schedules` names a job, which it has none of, and
 * `wow.peers` another service. Here among the integration tests, as it starts a host for each refusal.
 */
class DocumentedKeysGuardTest {
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `a value a numeric or duration key cannot take should be accepted, or refused naming the key`() {
        startHost(*BASE).close()
        val keys = DocumentedKeys.all.filter { key ->
            key.isNumeric && !key.isProvider && UNSERVED.none { key.name.startsWith(it) }
        }
        keys.size.assert().isGreaterThan(100)
        // One round for each kind of value, so that a key is set once in each start.
        val misnamed = listOf(0, 1).flatMap { round ->
            misnamed(
                keys.mapNotNull { key ->
                    DocumentedKeys.impossibleValues(key).getOrNull(round)?.let { key.key to it }
                }.toMap(),
            )
        }
        misnamed.assert().describedAs(
            "Each setting a start refused naming another key, or failing without a WowConfigurationException.",
        ).isEmpty()
    }

    /**
     * The settings of [probes] whose start fails naming none of them: started all together, and again without each
     * one a refusal names, until the host starts with the rest, which it accepts.
     */
    private fun misnamed(probes: Map<String, String>): List<String> = buildList {
        var pending = probes
        while (pending.isNotEmpty()) {
            val (refused, named) = startRefusal(pending) ?: break
            val culprits = when {
                named.size == 1 -> named
                // A group above several keys set: each alone holds its own refusal or acceptance.
                named.isNotEmpty() -> named.onEach { one -> addIfMisnamed(one, pending.getValue(one)) }
                // Refused naming no key set: each alone, to say which one.
                else -> listOf(
                    pending.keys.firstOrNull { addIfMisnamed(it, pending.getValue(it)) }
                        ?: throw AssertionError("A start with $pending failed naming none of them: $refused"),
                )
            }
            pending = pending - culprits.toSet()
        }
    }

    /** Adds [key] set to [value] when its start alone fails naming another key; whether it did. */
    private fun MutableList<String>.addIfMisnamed(key: String, value: String): Boolean {
        val (refused, named) = startRefusal(mapOf(key to value)) ?: return false
        return named.isEmpty().also { if (it) add("$key=$value: refused as $refused") }
    }

    /**
     * The refusal of a start of the test host with [settings] over [BASE], as the key it names and the keys of
     * [settings] it names (the key, or keys under a group it names); null when the host starts. A failure that is not a
     * [WowConfigurationException] names no key.
     */
    private fun startRefusal(settings: Map<String, String>): Pair<String, List<String>>? {
        val failure = runCatching { startHost(*BASE, *settings.toList().toTypedArray()).close() }.exceptionOrNull()
            ?: return null
        val refusal = generateSequence(failure) { it.cause }.filterIsInstance<WowConfigurationException>().firstOrNull()
            ?: return "${failure.javaClass.simpleName}: ${failure.message}" to emptyList()
        val named = settings.keys.filter { key ->
            key == refusal.key || key.startsWith(refusal.key + ".") || key.startsWith(refusal.key + "[")
        }
        return refusal.key to named
    }

    private companion object {
        /** The maps whose entries the test host cannot serve: it has no job, and a peer is another service. */
        val UNSERVED: List<String> = listOf("wow.schedules.", "wow.peers.")

        /** What every start sets: a second store, the entry the `wow.stores.<name>` keys name. */
        val BASE: Array<Pair<String, Any>> = arrayOf("wow.stores.main.type" to "memory")
    }
}

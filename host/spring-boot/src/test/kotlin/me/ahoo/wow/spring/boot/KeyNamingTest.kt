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
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The naming rules of the keys, on the host's metadata and every module's: a feature switch ends with `.enabled` and a policy switch is a
 * verb phrase, `require-*`, `allow-*` or `trust-*` (design rule K4); a retry's settings are named alike everywhere,
 * `max-attempts`, `initial-backoff`, `max-backoff`, `multiplier`, `jitter` (K5); a value a key takes is a word of its
 * own, never `on`, `off`, `yes` or `no`, and a switch the node may decide takes `auto`, `true` and `false`. A key that
 * is neither is on an allowlist with its reason, and an allowlisted key that no longer exists fails, so no entry
 * outlives its key.
 */
class KeyNamingTest {
    /** The metadata of the host and of every module that ships its own keys. */
    private val documents = METADATA.map { WowJson.mapper.readTree(it.readText()) }
    private val properties = documents.flatMap {
        it["properties"]
    }.associate { it["name"].asString() to it["type"].asString() }

    @Test
    fun `a boolean key should be a feature switch or a policy, unless allowed`() {
        val switches = properties.filterValues { it == "java.lang.Boolean" }.keys.filterNot { key ->
            val leaf = key.substringAfterLast('.')
            key.endsWith(".enabled") || POLICY_VERBS.any { leaf.startsWith(it) }
        }
        switches.filterNot { it in BOOLEANS }.assert()
            .describedAs("End a feature switch with .enabled, a policy with require-/allow-/trust-, or allow it.")
            .isEmpty()
    }

    @Test
    fun `an enabled key should be a boolean, unless allowed`() {
        properties.filter { (key, type) -> key.endsWith(".enabled") && type != "java.lang.Boolean" }.keys
            .filterNot { it in SWITCHES }.assert()
            .describedAs("A .enabled key is a Boolean feature switch; allow one that the node may also decide.")
            .isEmpty()
    }

    @Test
    fun `no value should be on, off, yes or no, and a switch should be auto, true or false`() {
        val defaults = documents.flatMap { it["properties"] }.mapNotNull { property ->
            property["defaultValue"]?.takeIf { it.isString }?.let { property["name"].asString() to it.asString() }
        }
        val hinted = documents.flatMap { it["hints"]?.toList().orEmpty() }.flatMap { hint ->
            hint["values"].toList().map { value -> hint["name"].asString() to value["value"].asString() }
        }
        val constants = properties.mapNotNull { (key, type) ->
            enumOf(
                type
            )?.let { key to it }
        }.flatMap { (key, type) ->
            type.enumConstants.map { key to (it as Enum<*>).name.lowercase().replace('_', '-') }
        }
        (defaults + hinted + constants).filter { (_, value) -> value.lowercase() in YES_NO }.assert()
            .describedAs("Name a value for what it does, such as disabled or none, never on, off, yes or no.")
            .isEmpty()
        SWITCHES.keys.forEach { key ->
            val type = checkNotNull(enumOf(properties.getValue(key))) { "$key is no enumeration." }
            type.enumConstants.map { (it as Enum<*>).name.lowercase() }.assert().describedAs(key)
                .containsExactly("auto", "true", "false")
        }
    }

    /** The enumeration a key's metadata type names, null for any other type. */
    private fun enumOf(type: String): Class<*>? = runCatching { Class.forName(type) }.getOrNull()?.takeIf { it.isEnum }

    @Test
    fun `a retry's keys should be named alike, unless allowed`() {
        val retries = properties.keys.filter { key ->
            val leaf = key.substringAfterLast('.')
            RETRY_WORDS.any { it in leaf } && leaf !in RETRY_LEAVES
        }
        retries.filterNot { it in RETRIES }.assert()
            .describedAs("Name a retry's settings ${RETRY_LEAVES.joinToString()}, or allow the key.")
            .isEmpty()
    }

    @Test
    fun `every allowed key should still exist`() {
        (BOOLEANS.keys + RETRIES.keys + SWITCHES.keys).filterNot { it in properties }.assert()
            .describedAs("Remove the entries of keys that are gone.")
            .isEmpty()
    }

    private companion object {
        /** The host's metadata, then each module's: the analytics catalog's, the view store's and CosId's. */
        val METADATA: List<Path> = listOf(
            ConfigurationMetadata.FILE,
            Path.of("../../platform/analytics/catalog/server").resolve(ConfigurationMetadata.FILE),
            Path.of("../../platform/view-store/view-store-server").resolve(ConfigurationMetadata.FILE),
            Path.of("../../extensions/cosid").resolve(ConfigurationMetadata.FILE),
        )
        val YES_NO = setOf("on", "off", "yes", "no")
        val POLICY_VERBS = listOf("require-", "allow-", "trust-")
        val RETRY_WORDS = listOf("backoff", "attempts", "retries", "retry")
        val RETRY_LEAVES = setOf(
            "max-attempts",
            "initial-backoff",
            "max-backoff",
            "multiplier",
            "jitter",
            "max-retry-after",
        )

        /** The boolean keys that are neither a feature switch nor a policy, each with why. */
        val BOOLEANS: Map<String, String> = mapOf(
            "wow.health.topics.required" to "how strict a health check is: a missing topic makes the node not ready",
            "wow.node.standby" to "the state the node starts in, not a feature",
            "wow.query.abac.match-missing-tag-key" to "a matching rule of the ABAC tags",
            "wow.query.budgets.http.unindexed-sort-expensive" to "what the budget counts as an expensive operator",
            "wow.query.budgets.in-process.unindexed-sort-expensive" to "what the budget counts as an expensive operator",
            "wow.query.count-cache.paged-totals" to "what the count cache covers, not a switch of its own",
            "wow.query.sensitivity.display-comparable" to "a policy on sensitive fields, named by what it permits",
        )

        /**
         * The `.enabled` keys that are not Boolean, each with why: a switch the node may also decide for itself takes
         * `auto` beside `true` and `false`, and keeps the switch's name, so the key reads as the feature it turns on.
         */
        val SWITCHES: Map<String, String> = mapOf(
            "wow.ops.publish.enabled" to
                "auto, the default, publishes the ledger once the ops service is deployed; true or false decide it",
        )

        /** The retry keys named otherwise, each with why. */
        val RETRIES: Map<String, String> = mapOf(
            "wow.subscription.parked.max-retries" to
                "counts the retries after an entry parked, which max-attempts would not say",
        )
    }
}

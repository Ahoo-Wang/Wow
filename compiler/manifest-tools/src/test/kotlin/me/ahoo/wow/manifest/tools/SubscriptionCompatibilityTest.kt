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

package me.ahoo.wow.manifest.tools

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.copyWith
import org.junit.jupiter.api.Test

/** A subscription removed ([removedSubscription]): the handler added beside it that is likely its rename. */
class SubscriptionCompatibilityTest {
    private val baseline = ManifestCompatibilityTest.BASELINE
    private val removed = baseline.contexts.single().subscriptions.single()

    /** The baseline with [removed] replaced by [added], a handler of another name. */
    private fun replacedBy(added: SubscriptionManifest): Manifest =
        baseline.copyWith(contexts = baseline.contexts.map { it.copyWith(subscriptions = listOf(added)) })

    private fun breaking(current: Manifest): List<String> =
        ManifestCompatibility.compare(baseline, current).breaking.map { it.toString() }

    private val renamed = removed.copyWith(
        name = "CartProjector.onItemPut",
        function = removed.function.copyWith(name = "onItemPut"),
    )

    @Test
    fun `a handler of the same processor reading the same source should be named its likely rename`() {
        breaking(replacedBy(renamed)).assert().containsExactly(
            "BREAKING contexts[shop].subscriptions[CartProjector.onItemAdded]: subscription removed: if " +
                "CartProjector.onItemPut is its handler renamed, annotate it @Name(\"onItemAdded\") to keep the " +
                "subscription, its checkpoint and its executions",
        )
    }

    @Test
    fun `a handler of another kind, processor, source context or aggregates should be no rename`() {
        listOf(
            renamed.copyWith(kind = SubscriptionKind.REACTOR),
            renamed.copyWith(function = renamed.function.copyWith(processor = "CartReporter")),
            renamed.copyWith(source = renamed.source.copyWith(context = "billing")),
            renamed.copyWith(source = renamed.source.copyWith(aggregates = listOf("order"))),
        ).forEach { added ->
            breaking(replacedBy(added)).assert().describedAs(added.toString()).containsExactly(
                "BREAKING contexts[shop].subscriptions[CartProjector.onItemAdded]: subscription removed",
            )
        }
    }
}

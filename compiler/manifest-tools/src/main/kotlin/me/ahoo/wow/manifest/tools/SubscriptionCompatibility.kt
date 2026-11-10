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

import me.ahoo.wow.manifest.SubscriptionManifest

// The comparison of a subscription with its baseline, as [ManifestCompatibility] states it.

/**
 * Reports [old] removed, breaking: its checkpoint and executions are lost. When a handler of the same processor
 * reading the same source is added in [current], it is likely [old]'s function renamed, and the report says to
 * keep the subscription with `@Name("<old function>")` on it.
 */
internal fun Changes.removedSubscription(
    path: String,
    old: SubscriptionManifest,
    baseline: List<SubscriptionManifest>,
    current: List<SubscriptionManifest>,
) {
    val names = baseline.map { it.name }.toSet()
    val renamed = current.filter { it.name !in names }.firstOrNull { added ->
        added.kind == old.kind && added.function.processor == old.function.processor &&
            added.source.context == old.source.context &&
            added.source.aggregates.toSet() == old.source.aggregates.toSet()
    }
    val hint = renamed?.let {
        ": if ${it.name} is its handler renamed, annotate it @Name(\"${old.function.name}\") to keep the " +
            "subscription, its checkpoint and its executions"
    }.orEmpty()
    breaking(path, "subscription removed$hint")
}

internal fun Changes.compareSubscription(path: String, baseline: SubscriptionManifest, current: SubscriptionManifest) {
    if (baseline.kind != current.kind) {
        breaking("$path.kind", "kind changed from ${baseline.kind} to ${current.kind}")
    }
    if (baseline.function != current.function) {
        breaking("$path.function", "function changed from ${baseline.function} to ${current.function}")
    }
    val oldSource = baseline.source
    val newSource = current.source
    if (oldSource.context != newSource.context || oldSource.aggregates.toSet() != newSource.aggregates.toSet()) {
        breaking(
            "$path.source",
            "source stream changed from ${oldSource.context}${oldSource.aggregates} to " +
                "${newSource.context}${newSource.aggregates}: its checkpoint belongs to the old one",
        )
    }
    if (oldSource.events.toSet() != newSource.events.toSet()) {
        additive("$path.source.events", "events changed from ${oldSource.events} to ${newSource.events}")
    }
    val oldDerives = baseline.derives
    val newDerives = current.derives
    if (oldDerives != null && newDerives != null) {
        compareValues("$path.derives", oldDerives, newDerives, "derived command")
    } else if (oldDerives != newDerives) {
        additive(
            "$path.derives",
            if (newDerives == null) "derived commands no longer declared" else "derived commands declared"
        )
    }
    val oldCalls = baseline.calls.orEmpty()
    val newCalls = current.calls.orEmpty()
    if (oldCalls != newCalls) {
        compareValues("$path.calls", oldCalls, newCalls, "called command")
    }
    compareReceipts("$path.rejected", baseline.rejected, current.rejected, "rejections")
    compareReceipts("$path.committed", baseline.committed, current.committed, "commits")
}

/**
 * The commands an `@OnRejected` or `@OnCommitted` handler takes the receipts of: null when
 * the handler takes none, empty for any command its saga derives. A handler that stops taking a kind of receipt,
 * or a command's, breaks; one that starts, or widens to any command, is additive.
 */
private fun Changes.compareReceipts(path: String, baseline: List<String>?, current: List<String>?, receipts: String) {
    when {
        baseline == current -> Unit
        current == null -> breaking(path, "no longer handles $receipts")
        baseline == null -> additive(path, "handles $receipts")
        current.isEmpty() -> additive(path, "handles the $receipts of any derived command")
        baseline.isEmpty() -> breaking(path, "handles the $receipts of $current only, not of any derived command")
        else -> compareValues(path, baseline, current, "command with handled $receipts")
    }
}

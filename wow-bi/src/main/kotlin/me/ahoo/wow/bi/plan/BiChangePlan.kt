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

package me.ahoo.wow.bi.plan

import me.ahoo.wow.bi.BiConsumerIdentity
import me.ahoo.wow.bi.BiDurableEntry
import me.ahoo.wow.bi.BiObjectKey
import me.ahoo.wow.bi.BiOwnedObject
import me.ahoo.wow.bi.BiScriptOperation

/** What a script does to one desired object. */
internal enum class BiObjectAction {
    /** The object is missing: create it. */
    CREATE,

    /** The object exists but cannot be kept: drop it (for a consumer, while its stream is paused) and recreate it. */
    REPLACE,

    /** The object exists as desired: leave it untouched, so no statement is rendered for it. */
    KEEP,
}

/**
 * Every decision one script makes, taken in one place by [BiReconciler]; the renderers only translate it into SQL.
 */
internal data class BiChangePlan(
    val operation: BiScriptOperation,
    /** `false` for an offline preview: nothing was observed, so every statement must fail if its object exists. */
    val authoritative: Boolean,
    val consumerIdentity: BiConsumerIdentity,
    private val actions: Map<BiObjectKey, BiObjectAction>,
    /** The action for objects the plan does not list; `null` makes an unlisted object an error. */
    private val unlistedAction: BiObjectAction? = null,
    /** Owned objects dropped before anything else: every one on RESET, the undesired non-stores on DEPLOY. */
    val drops: List<BiOwnedObject>,
    /** The anchor's record of the stores and queues that exist once the script has run up to the anchor. */
    val durableInventory: List<BiDurableEntry>,
) {
    /** The action for a desired object. */
    fun action(key: BiObjectKey): BiObjectAction = checkNotNull(actions[key] ?: unlistedAction) {
        "BI object [${key.database}.${key.name}] has no planned action"
    }

    fun renders(key: BiObjectKey): Boolean = action(key) != BiObjectAction.KEEP

    fun replaces(key: BiObjectKey): Boolean = action(key) == BiObjectAction.REPLACE

    companion object {
        /** A plan that creates every object; used where nothing is observed, such as computing definitions. */
        fun creatingAll(operation: BiScriptOperation, consumerIdentity: BiConsumerIdentity): BiChangePlan =
            BiChangePlan(
                operation = operation,
                authoritative = false,
                consumerIdentity = consumerIdentity,
                actions = emptyMap(),
                unlistedAction = BiObjectAction.CREATE,
                drops = emptyList(),
                durableInventory = emptyList(),
            )
    }
}

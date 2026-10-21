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

package me.ahoo.wow.viewstore.domain.view

import me.ahoo.wow.api.annotation.Upgrade
import me.ahoo.wow.viewstore.api.ViewKind
import me.ahoo.wow.viewstore.api.view.ViewCreated
import me.ahoo.wow.viewstore.api.view.ViewSaved
import tools.jackson.databind.node.ObjectNode

// region upgrades
/**
 * The 9.x records of a view, read as revision 2 (decision D267): 9.x recorded no `kind`, which it defined as the
 * config's own `kind` (its `ViewKind`), so the upgrade takes it from there. A 9.x config without a kind, or with one
 * that is no [ViewKind], fails the read: the view is not guessed into a kind, and the read names the view.
 */
object ViewUpgrades {
    @Upgrade(event = ViewCreated::class, from = 1)
    @JvmStatic
    fun viewCreatedFrom1(node: ObjectNode): ObjectNode = withKind(node)

    @Upgrade(event = ViewSaved::class, from = 1)
    @JvmStatic
    fun viewSavedFrom1(node: ObjectNode): ObjectNode = withKind(node)

    private fun withKind(node: ObjectNode): ObjectNode {
        val named = node.get(CONFIG)?.get(ViewConfigs.KIND)?.takeIf { it.isString }?.stringValue()
        val kind = requireNotNull(ViewKind.of(named)) {
            "A 9.x view's config has no kind among ${ViewKind.entries.map { it.value }}: ${node.get(
                CONFIG
            )?.get(ViewConfigs.KIND)}."
        }
        return node.put(KIND, kind.value)
    }

    private const val CONFIG = "config"
    private const val KIND = "kind"
}
// endregion

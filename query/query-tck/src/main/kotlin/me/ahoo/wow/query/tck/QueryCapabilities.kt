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

package me.ahoo.wow.query.tck

import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.SupportMode
import org.junit.jupiter.api.Assumptions

/**
 * Skips the test unless the storage supports [feature]: [mode] is what it declares for it, at [fact] (such as
 * `storage.paging.keyset`). The skip says the fact and its value, so a report shows which declaration declined it, and
 * a backend that declined a feature by mistake sees it.
 */
fun assumeSupported(mode: SupportMode, fact: String, feature: String) {
    Assumptions.assumeTrue(mode != SupportMode.NONE) { "Skipped: the storage declares $fact = $mode ($feature)." }
}

/**
 * Skips the test unless the storage binds [capability] to [field]: [binding] is that binding, null when it binds none.
 * The skip names the field and the capability, as [assumeSupported] names a storage fact.
 */
fun assumeBound(binding: Any?, field: String, capability: QueryCapability, feature: String) {
    Assumptions.assumeTrue(binding != null) {
        "Skipped: the storage binds no $capability to $field ($feature)."
    }
}

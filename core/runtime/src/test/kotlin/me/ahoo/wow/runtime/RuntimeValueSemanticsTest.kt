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

package me.ahoo.wow.runtime

import me.ahoo.wow.tck.values.ValueSemanticsSpec

/** The hand-written equals of the runtime compare every field (finding R5.B-13). */
class RuntimeValueSemanticsTest : ValueSemanticsSpec() {
    override val packages: List<String> = listOf("me.ahoo.wow")

    override val excluded: Map<String, String> = mapOf(
        "me.ahoo.wow.runtime.subscription.BackpressureOptions#retries" to
            "built from initialBackoff and maxBackoff, which equals compares",
        "me.ahoo.wow.runtime.subscription.SystemicFailureOptions#probes" to
            "built from initialBackoff and maxBackoff, which equals compares",
    )
}

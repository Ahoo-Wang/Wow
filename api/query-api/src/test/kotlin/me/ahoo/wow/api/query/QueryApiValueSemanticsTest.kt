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

package me.ahoo.wow.api.query

import me.ahoo.wow.tck.values.ValueSemanticsSpec

/** The hand-written equals of the query protocol compare every field (finding R5.B-13). */
class QueryApiValueSemanticsTest : ValueSemanticsSpec() {
    override val packages: List<String> = listOf("me.ahoo.wow")

    override val excluded: Map<String, String> = mapOf(
        "me.ahoo.wow.api.query.*#operator" to "each filter class's own constant: equals compares the class",
        "me.ahoo.wow.api.query.AfterNowFilter#offsetDuration" to "parsed from the offset, which equals compares",
        "me.ahoo.wow.api.query.BeforeNowFilter#offsetDuration" to "parsed from the offset, which equals compares",
    )
}

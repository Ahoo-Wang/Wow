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

package me.ahoo.wow.query

import me.ahoo.wow.tck.values.ValueSemanticsSpec

/** The hand-written equals of the query engine compare every field (finding R5.B-13). */
class QueryValueSemanticsTest : ValueSemanticsSpec() {
    override val packages: List<String> = listOf("me.ahoo.wow")

    override val excluded: Map<String, String> = mapOf(
        "me.ahoo.wow.query.dataset.DatasetQueryModelProfile#model" to "the profile's constant model, DATASET",
        "me.ahoo.wow.query.dataset.DatasetQueryModelProfile#firstEventTimeField" to "read from the dataset, which equals compares",
        "me.ahoo.wow.query.dataset.DatasetQueryModelProfile#fixedTenantId" to "read from the dataset, which equals compares",
    ) + listOf(
        "identityField", "payloadField", "eventTimeField", "cursorUniqueKey", "nameField", "model",
        "firstEventTimeField", "fixedTenantId",
    ).associate {
        "me.ahoo.wow.query.schema.EventsQueryModelProfile#$it" to
            "the events model's constant, or read from the stored names, which equals compares"
    } + mapOf(
        "me.ahoo.wow.query.schema.MaskRule#compiled" to "compiled from the mask, which equals compares",
    ) + listOf("keyCount", "hasOrderedKeys", "hash", "fixedField").associate {
        "me.ahoo.wow.query.schema.QueryPathTemplate#$it" to "computed from the segments, which equals compares"
    }
}

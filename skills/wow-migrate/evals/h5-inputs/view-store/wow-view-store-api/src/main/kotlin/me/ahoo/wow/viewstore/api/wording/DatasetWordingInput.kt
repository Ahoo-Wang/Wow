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

package me.ahoo.wow.viewstore.api.wording

import tools.jackson.databind.node.ObjectNode

/**
 * The body of the wording route's `PUT`: the dataset is the path's, the tenant the path's and the application the
 * request's.
 */
data class DatasetWordingInput(
    val title: String? = null,
    val recordNoun: String? = null,
    val fields: ObjectNode? = null,
) {
    fun toCommand(dataset: String): SetDatasetWording = SetDatasetWording(
        dataset = dataset,
        title = title,
        recordNoun = recordNoun,
        fields = fields,
    )
}

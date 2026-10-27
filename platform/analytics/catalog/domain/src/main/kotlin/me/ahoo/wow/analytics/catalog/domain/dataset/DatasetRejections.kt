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

package me.ahoo.wow.analytics.catalog.domain.dataset

import me.ahoo.wow.api.annotation.ErrorCode

/** The codes the dataset aggregate rejects a command with (-2). */
object DatasetRejections {
    @ErrorCode(
        description = "The dataset's definition does not hold against the catalog: its sources, joins, references or expressions.",
        status = 400,
    )
    const val DEFINITION_INVALID = "analytics.catalog.definition-invalid"

    @ErrorCode(description = "An aggregate of the catalog's sources already takes the dataset's name.", status = 409)
    const val NAME_TAKEN = "analytics.catalog.name-taken"

    @ErrorCode(description = "The dataset was never published.", status = 409)
    const val NOT_PUBLISHED = "analytics.catalog.not-published"

    @ErrorCode(description = "The warehouse did not create the dataset version's view.", status = 503)
    const val VIEW_FAILED = "analytics.catalog.view-failed"

    @ErrorCode(description = "No publication of the dataset awaits an approver.", status = 409)
    const val NOT_REQUESTED = "analytics.catalog.not-requested"
}

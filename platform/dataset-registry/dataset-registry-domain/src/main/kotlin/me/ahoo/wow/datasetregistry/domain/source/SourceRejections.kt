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

package me.ahoo.wow.datasetregistry.domain.source

import me.ahoo.wow.api.annotation.ErrorCode

/** The codes a [Source] rejects a command with. */
object SourceRejections {
    @ErrorCode(description = "The request is not a valid change of the source: its id, columns or values.", status = 400)
    const val INVALID = "dataset-registry.invalid"

    @ErrorCode(description = "The warehouse has no such table in the configured databases.", status = 404)
    const val TABLE_NOT_FOUND = "dataset-registry.table-not-found"

    @ErrorCode(
        description = "The table is a Wow table, described by its trusted wow_catalog row: not an analyst's to draft or revise.",
        status = 409,
    )
    const val WOW_SOURCE = "dataset-registry.wow-source"

    @ErrorCode(
        description = "The source is not complete: its tenant column is not declared or a string column is not reviewed.",
        status = 409,
    )
    const val INCOMPLETE = "dataset-registry.incomplete"

    @ErrorCode(
        description = "A Wow source's value is only tightened: a lower protection takes a privileged reviewer's approval.",
        status = 409,
    )
    const val LOOSENING = "dataset-registry.loosening"

    @ErrorCode(description = "A reference names an aggregate the catalog does not know.", status = 400)
    const val UNKNOWN_AGGREGATE = "dataset-registry.unknown-aggregate"

    @ErrorCode(description = "The source's table is no longer in the warehouse.", status = 409)
    const val TABLE_MISSING = "dataset-registry.table-missing"
}

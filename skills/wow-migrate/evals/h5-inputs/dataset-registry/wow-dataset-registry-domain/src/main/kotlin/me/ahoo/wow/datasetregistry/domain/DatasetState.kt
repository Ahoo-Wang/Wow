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

package me.ahoo.wow.datasetregistry.domain

import me.ahoo.wow.api.annotation.OnSourcing
import me.ahoo.wow.datasetregistry.api.DatasetDrafted
import me.ahoo.wow.datasetregistry.api.DatasetPublished
import me.ahoo.wow.datasetregistry.api.DatasetRetired
import me.ahoo.wow.datasetregistry.api.DatasetRevised
import me.ahoo.wow.datasetregistry.api.FieldSensitivity
import me.ahoo.wow.datasetregistry.api.SensitivityReviewed
import me.ahoo.wow.datasetregistry.api.SkippedColumn
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.node.ObjectNode

/**
 * A dataset of the registry: its draft (a declaration being written, and the sensitivity reviews of its fields) and
 * the version it serves, if any. The aggregate id is the dataset's name.
 */
class DatasetState(val id: String) {
    /** The table the dataset was drafted from. */
    var table: String = ""
        private set

    /** The draft declaration, in the format of a dataset file, without any `sensitive` key. */
    var document: ObjectNode = JsonSerializer.createObjectNode()
        private set

    var skipped: List<SkippedColumn> = emptyList()
        private set

    /** The reviewer's decision on each reviewed field. */
    var reviews: Map<String, FieldSensitivity> = emptyMap()
        private set

    /** The declaration served, `null` before the first publication and after a retirement. */
    var published: ObjectNode? = null
        private set

    /** The last version published, `0` before the first publication. */
    var publishedVersion: Int = 0
        private set

    @OnSourcing
    fun onDrafted(event: DatasetDrafted) {
        table = event.table
        document = event.document
        skipped = event.skipped
    }

    @OnSourcing
    fun onRevised(event: DatasetRevised) {
        document = event.document
        reviews = event.reviews
    }

    @OnSourcing
    fun onReviewed(event: SensitivityReviewed) {
        reviews = reviews + event.fields
    }

    @OnSourcing
    fun onPublished(event: DatasetPublished) {
        published = event.document
        publishedVersion = event.version
    }

    @OnSourcing
    fun onRetired(@Suppress("UNUSED_PARAMETER") event: DatasetRetired) {
        published = null
    }
}

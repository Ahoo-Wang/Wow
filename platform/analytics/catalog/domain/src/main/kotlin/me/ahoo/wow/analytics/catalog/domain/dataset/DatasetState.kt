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

import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDefinition
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDrafted
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetRevised
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetStatus

/** A dataset of the catalog: its name, its definition and the sources it reads. */
class DatasetState {
    var context: String = ""
        private set
    var name: String = ""
        private set
    var title: String? = null
        private set
    var definition: DatasetDefinition? = null
        private set

    /** The ids of the catalog's sources the definition reads. */
    var sources: List<String> = emptyList()
        private set
    var status: DatasetStatus = DatasetStatus.DRAFT
        private set

    /** Whether the dataset exists: it was drafted. */
    val exists: Boolean get() = name.isNotEmpty()

    fun onSourcing(event: DatasetDrafted) {
        context = event.context
        name = event.name
        title = event.title
        definition = event.definition
        sources = event.sources
        status = DatasetStatus.DRAFT
    }

    fun onSourcing(event: DatasetRevised) {
        title = event.title
        definition = event.definition
        sources = event.sources
    }
}

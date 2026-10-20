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

import me.ahoo.wow.analytics.catalog.domain.source.SourceState

/**
 * The catalog's sources as a dataset's definition reads them: the state of each source by its id, as the catalog
 * has it now. The service reads the sources' snapshots.
 */
fun interface DatasetSources {
    /** The source [id], or null when the catalog has none. */
    suspend fun source(id: String): SourceState?
}

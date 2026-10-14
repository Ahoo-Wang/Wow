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

package me.ahoo.wow.platform.viewstore.api.wording

import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.api.annotation.Unaddressed

/**
 * Sets the wording a tenant's readers see over one dataset in one application: its title, its record noun and its
 * fields' labels, order and narrowing (the view engine's `DatasetOverrides`). The wording's id is derived on the
 * server from the application and the dataset, so the route names none:
 * `POST /view-store/dataset_wording/set-dataset-wording`. The expected version `If-Match: "0"` is "never written".
 */
@AllowCreate
@Summary("Set the wording of a dataset")
data class SetDatasetWording(
    val dataset: String,
    val title: String? = null,
    val recordNoun: String? = null,
    /** The fields' wording as the view engine reads it; the server checks its size, not its meaning. */
    val fields: Map<String, Any?>? = null,
)

/**
 * Clears a dataset's wording, which then reads as none: `POST /view-store/dataset_wording/clear-dataset-wording`.
 * Its id is derived as [SetDatasetWording]'s, so the request names none ([Unaddressed]).
 */
@Unaddressed
@Summary("Clear the wording of a dataset")
data class ClearDatasetWording(val dataset: String)

@Summary("A dataset's wording was set")
data class DatasetWordingSet(
    val dataset: String,
    val appId: String,
    val title: String?,
    val recordNoun: String?,
    val fields: Map<String, Any?>?,
)

@Summary("A dataset's wording was cleared")
data object DatasetWordingCleared

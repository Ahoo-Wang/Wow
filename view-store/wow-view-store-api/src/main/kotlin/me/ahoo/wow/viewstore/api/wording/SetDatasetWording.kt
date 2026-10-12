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

import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Summary
import tools.jackson.databind.node.ObjectNode

/**
 * Sets the wording a tenant's readers see over one dataset in one application: its title, its record noun and its
 * fields' labels, order and narrowing (the view engine's `DatasetOverrides`). The wording's id is derived on the
 * server from the tenant, the application and the dataset, so this command has no generated route: the view store's
 * wording route dispatches it. The expected version `0` is "never written".
 */
@Summary("Set the wording of a dataset")
@AllowCreate
@CommandRoute(enabled = false)
data class SetDatasetWording(
    val dataset: String,
    val title: String? = null,
    val recordNoun: String? = null,
    /** The fields' wording as the view engine reads it; the server checks its shape and size, not its meaning. */
    val fields: ObjectNode? = null,
)

data class DatasetWordingSet(
    val dataset: String,
    val appId: String,
    val title: String?,
    val recordNoun: String?,
    val fields: ObjectNode?,
)

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

package me.ahoo.wow.analytics.catalog.api.dataset

import me.ahoo.wow.api.annotation.Summary

/** The dataset [name] of [context] was drafted from [definition], which reads the catalog's [sources]. */
@Summary("A dataset was drafted")
data class DatasetDrafted(
    val context: String,
    val name: String,
    val definition: DatasetDefinition,
    val sources: List<String>,
    val title: String? = null,
)

/** The dataset's definition is now [definition], which reads the catalog's [sources]. */
@Summary("A dataset was revised")
data class DatasetRevised(val definition: DatasetDefinition, val sources: List<String>, val title: String? = null)

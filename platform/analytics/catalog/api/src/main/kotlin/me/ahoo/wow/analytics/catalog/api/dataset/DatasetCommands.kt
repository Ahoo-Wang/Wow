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

import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Summary

/**
 * Drafts the dataset [name] of [context] from its [definition] (decision D259 7.2b-2): the dataset's id is
 * [me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext.datasetIdOf] of [context] and [name], the name its
 * queries read it by. The definition is checked against the catalog as it is now: its sources are there and usable,
 * its references name their columns, its joins key on the tenant, its expressions use only the functions allowed.
 */
@CreateAggregate
@Summary("Draft a dataset over the catalog's sources")
data class DraftDataset(
    val context: String,
    val name: String,
    val definition: DatasetDefinition,
    val title: String? = null,
)

/** Replaces the dataset's [definition] and [title], checked against the catalog as a draft's is. */
@Summary("Revise a dataset's definition")
data class ReviseDataset(val definition: DatasetDefinition, val title: String? = null)

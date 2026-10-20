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

/**
 * Publishes the dataset's definition as its next version (decision D259 7.2b-2): compiled against the catalog as it
 * is now into the version's own view, `ds_<context>_<name>_v<N>`, which the publication creates in the warehouse (the
 * only way the catalog changes the warehouse, audited by the event, decision D251 ④); queries then read that version.
 * A definition that compiles to what is published already changes nothing.
 */
@Summary("Publish the dataset's definition as its next version")
data object PublishDataset

/** Switches the dataset's queries back to its published [version], whose view is created again if it is gone. */
@Summary("Switch a dataset back to an earlier version")
data class RollbackDataset(val version: Int)

/**
 * Retires the dataset: its queries stop; its views stay in the warehouse for the retention (7 days by default), then
 * the catalog drops them. Publishing it again before then brings it back.
 */
@Summary("Retire a dataset")
data object RetireDataset

/** Drops the views of a dataset retired for the retention; the catalog schedules it when it retires one. */
@Summary("Drop a retired dataset's views")
data object DropDatasetViews

/**
 * Compiles the dataset's published definition again against the catalog as it is now, because a source it reads
 * changed (decision D259 7.2b-2): what compiles the same changes nothing; what compiles otherwise (a source's values
 * now more protected, say) is published as the next version; what no longer compiles marks the dataset
 * `NEEDS_REVISION`. The catalog sends it when a source changes.
 */
@Summary("Compile a published dataset again after a source changed")
data object RecompileDataset

/** Approves, as an approver, the publication of the dataset's definition that was requested: it is published. */
@Summary("Approve the requested publication of a dataset")
data object ApproveDatasetPublication

/** Refuses the requested publication of a dataset, for [reason]. */
@Summary("Refuse the requested publication of a dataset")
data class RefuseDatasetPublication(val reason: String)

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

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.annotation.StaticTenantId
import me.ahoo.wow.datasetregistry.api.DatasetDrafted
import me.ahoo.wow.datasetregistry.api.DatasetPublished
import me.ahoo.wow.datasetregistry.api.DatasetRetired
import me.ahoo.wow.datasetregistry.api.DatasetRevised
import me.ahoo.wow.datasetregistry.api.DraftDataset
import me.ahoo.wow.datasetregistry.api.PublishDataset
import me.ahoo.wow.datasetregistry.api.RetireDataset
import me.ahoo.wow.datasetregistry.api.ReviewSensitivity
import me.ahoo.wow.datasetregistry.api.ReviseDataset
import me.ahoo.wow.datasetregistry.api.SensitivityReviewed
import reactor.core.publisher.Mono

/**
 * A dataset of the registry (dataset platform design §4.1, §4.2). It is drafted once from a table, revised and
 * reviewed any number of times, and published when its draft passes the publication gate:
 *
 * 1. the declaration is valid, its scope included (a tenant column);
 * 2. every field holding strings has a reviewed sensitivity, "not sensitive" included: an unreviewed field blocks the
 *    publication rather than being served as not sensitive;
 * 3. the [DatasetPublicationCheck] proves this instance can serve it: its name is free and it compiles against its
 *    table.
 *
 * The registry is the platform's, not a tenant's: the aggregate has a static tenant.
 */
@AggregateRoot
@StaticTenantId
class Dataset(private val state: DatasetState) {

    @OnCommand
    fun onDraft(command: DraftDataset, inspector: DatasetTableInspector): Mono<DatasetDrafted> {
        DatasetDocuments.requireName(command.id)
        val table = command.table.trim()
        if (table.isEmpty()) {
            throw DatasetRegistryException.invalid(
                "Dataset [${command.id}] needs a table to draft from."
            )
        }
        return inspector.draft(table, command.source?.trim()?.takeIf(String::isNotEmpty)).map { draft ->
            DatasetDocuments.requireNoSensitivity(command.id, draft.document)
            DatasetDrafted(table = table, document = draft.document, skipped = draft.skipped)
        }
    }

    @OnCommand
    fun onRevise(command: ReviseDataset): DatasetRevised {
        DatasetDocuments.requireNoSensitivity(state.id, command.document)
        DatasetDocuments.parse(state.id, command.document)
        val before = DatasetDocuments.fields(state.document)
        val after = DatasetDocuments.fields(command.document)
        val kept = state.reviews.filterKeys { field -> after[field] != null && after[field] == before[field] }
        return DatasetRevised(document = command.document, reviews = kept)
    }

    // Each check states one way the review can be malformed.
    @Suppress("ThrowsCount")
    @OnCommand
    fun onReview(command: ReviewSensitivity): SensitivityReviewed {
        if (command.fields.isEmpty()) {
            throw DatasetRegistryException.invalid("Dataset [${state.id}]: a review names at least one field.")
        }
        val unknown = command.fields.keys - DatasetDocuments.fields(state.document).keys
        if (unknown.isNotEmpty()) {
            throw DatasetRegistryException.invalid("Dataset [${state.id}] declares no field $unknown.")
        }
        val negative = command.fields.filterValues { it.keepPrefix < 0 || it.keepSuffix < 0 }.keys
        if (negative.isNotEmpty()) {
            throw DatasetRegistryException.invalid(
                "Dataset [${state.id}]: $negative keep a negative number of characters."
            )
        }
        return SensitivityReviewed(command.fields)
    }

    // The command names the dataset; everything published is the draft's.
    @Suppress("UnusedParameter")
    @OnCommand
    fun onPublish(command: PublishDataset, check: DatasetPublicationCheck): Mono<DatasetPublished> {
        val unreviewed = DatasetDocuments.stringFields(state.document) - state.reviews.keys
        if (unreviewed.isNotEmpty()) {
            throw DatasetRegistryException.notPublishable(
                "Dataset [${state.id}] has string fields whose sensitivity is not reviewed: ${unreviewed.sorted()}.",
            )
        }
        val document = DatasetDocuments.withSensitivity(state.document, state.reviews)
        val dataset = try {
            DatasetDocuments.parse(state.id, document)
        } catch (error: DatasetRegistryException) {
            throw DatasetRegistryException.notPublishable(error.errorMsg, error)
        }
        return check.check(dataset).then(
            Mono.fromCallable { DatasetPublished(version = state.publishedVersion + 1, document = document) },
        )
    }

    @Suppress("UnusedParameter")
    @OnCommand
    fun onRetire(command: RetireDataset): DatasetRetired {
        if (state.published == null) {
            throw DatasetRegistryException.invalid("Dataset [${state.id}] is not published.")
        }
        return DatasetRetired(state.publishedVersion)
    }
}

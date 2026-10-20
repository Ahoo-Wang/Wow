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

package me.ahoo.wow.viewstore.domain.wording

import me.ahoo.wow.api.annotation.AggregateOwner
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.annotation.OwnerPolicy
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DefaultAggregateDeleted
import me.ahoo.wow.viewstore.api.wording.ClearDatasetWording
import me.ahoo.wow.viewstore.api.wording.DatasetWordingSet
import me.ahoo.wow.viewstore.api.wording.SetDatasetWording
import me.ahoo.wow.viewstore.domain.ViewApps.requireSameApp
import me.ahoo.wow.viewstore.domain.ViewApps.requiredAppId
import me.ahoo.wow.viewstore.domain.ViewStoreException
import me.ahoo.wow.viewstore.domain.view.ViewConfigs
import tools.jackson.databind.node.ObjectNode

/**
 * A tenant's wording over one dataset in one application: what its readers call the dataset, its records and its
 * fields (the view engine's dataset overrides, D86). The wording route derives its id and dispatches
 * [SetDatasetWording], which creates it on the first write (expected version `0`).
 *
 * The server checks its shape and size, not its meaning: a field the dataset no longer has is kept, and the view
 * engine passes over it, so a column dropped from a dataset does not make its wording unwritable.
 */
@AggregateRoot
@AggregateOwner(OwnerPolicy.ALWAYS)
class DatasetWording(private val state: DatasetWordingState) {
    companion object {
        /** The longest record noun: a word or two, 「订单」, "order". */
        const val MAX_NOUN_LENGTH = 40

        /**
         * The most the fields' wording may weigh, as JSON: a label and a narrowing for each of a few hundred fields,
         * well inside the request body a view's config is held to.
         */
        const val MAX_FIELDS_BYTES = 64 * 1024
    }

    @OnCommand
    fun onSet(command: CommandMessage<SetDatasetWording>): DatasetWordingSet {
        val body = command.body
        val appId = if (state.appId.isEmpty()) {
            command.requiredAppId()
        } else {
            command.requireSameApp(state.appId)
            state.appId
        }
        requireDataset(body.dataset)
        return DatasetWordingSet(
            dataset = body.dataset,
            appId = appId,
            title = body.title?.let(::requireTitle),
            recordNoun = body.recordNoun?.let(::requireNoun),
            fields = body.fields?.also(::requireFields),
        )
    }

    /** Clears the wording, which then reads as none; another application's reads as not found. */
    @OnCommand
    fun onClear(command: CommandMessage<ClearDatasetWording>): DefaultAggregateDeleted {
        command.requireSameApp(state.appId)
        return DefaultAggregateDeleted
    }

    /** A dataset name the store can index, and this wording's own once it was written. */
    private fun requireDataset(dataset: String) {
        if (dataset.isBlank() || dataset.length > ViewConfigs.MAX_ID_LENGTH) {
            throw ViewStoreException.invalid(
                "The wording's dataset must be 1 to ${ViewConfigs.MAX_ID_LENGTH} characters."
            )
        }
        if (state.dataset.isNotEmpty() && state.dataset != dataset) {
            throw ViewStoreException.invalid("The wording belongs to dataset [${state.dataset}].")
        }
    }

    private fun requireTitle(title: String): String {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || trimmed.length > ViewConfigs.MAX_TITLE_LENGTH) {
            throw ViewStoreException.invalid(
                "The wording's title must be 1 to ${ViewConfigs.MAX_TITLE_LENGTH} characters."
            )
        }
        return trimmed
    }

    private fun requireNoun(noun: String): String {
        val trimmed = noun.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NOUN_LENGTH) {
            throw ViewStoreException.invalid(
                "The wording's record noun must be 1 to $MAX_NOUN_LENGTH characters."
            )
        }
        return trimmed
    }

    private fun requireFields(fields: ObjectNode) {
        val bytes = fields.toString().toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_FIELDS_BYTES) {
            throw ViewStoreException.invalid("The wording's fields are $bytes bytes; the limit is $MAX_FIELDS_BYTES.")
        }
    }
}

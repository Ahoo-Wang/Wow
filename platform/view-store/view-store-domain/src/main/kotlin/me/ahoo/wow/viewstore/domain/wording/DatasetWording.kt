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

import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.viewstore.api.wording.ClearDatasetWording
import me.ahoo.wow.viewstore.api.wording.DatasetWordingCleared
import me.ahoo.wow.viewstore.api.wording.DatasetWordingSet
import me.ahoo.wow.viewstore.api.wording.SetDatasetWording
import me.ahoo.wow.viewstore.domain.ViewApps
import me.ahoo.wow.viewstore.domain.view.ViewConfigs

/**
 * A tenant's wording over one dataset in one application: what its readers call the dataset, its records and its
 * fields (the view engine's dataset overrides). It is the whole tenant's (`Ownership.TENANT`): every
 * reader of the tenant reads it, and the service's authorizer decides who writes it, which 9.x kept under the owner
 * `(shared)`. The service derives its id ([me.ahoo.wow.viewstore.domain.ScopedIds.wording]).
 *
 * The server checks its shape and size, not its meaning: a field the dataset no longer has is kept, and the view
 * engine passes over it.
 */
@AggregateRoot(ownership = Ownership.TENANT, lifecycle = [])
@Description("A tenant's wording over one dataset in one application")
class DatasetWording(private val state: DatasetWordingState) {
    @OnCommand
    fun onSet(command: Command<SetDatasetWording>): DatasetWordingSet {
        val appId = if (state.appId.isEmpty()) {
            ViewApps.appOf(command) ?: ViewApps.appRequired()
        } else {
            ViewApps.rejectOtherApp(command, state.appId)
            state.appId
        }
        val body = command.body
        problemOf(body)?.let(ViewApps::invalid)
        return DatasetWordingSet(
            dataset = body.dataset,
            appId = appId,
            title = body.title?.trim(),
            recordNoun = body.recordNoun?.trim(),
            fields = body.fields,
        )
    }

    /** Clears the wording, which then reads as none; a wording that says nothing changes nothing. */
    @OnCommand
    fun onClear(command: Command<ClearDatasetWording>): DatasetWordingCleared? {
        ViewApps.rejectOtherApp(command, state.appId)
        return if (state.isEmpty) null else DatasetWordingCleared
    }

    private fun problemOf(body: SetDatasetWording): String? = when {
        body.dataset.isBlank() || body.dataset.length > ViewConfigs.MAX_ID_LENGTH ->
            "The wording's dataset must be 1 to ${ViewConfigs.MAX_ID_LENGTH} characters."

        state.dataset.isNotEmpty() && state.dataset != body.dataset ->
            "The wording belongs to dataset [${state.dataset}]."

        body.title.isOutOf(ViewConfigs.MAX_TITLE_LENGTH) ->
            "The wording's title must be 1 to ${ViewConfigs.MAX_TITLE_LENGTH} characters."

        body.recordNoun.isOutOf(MAX_NOUN_LENGTH) ->
            "The wording's record noun must be 1 to $MAX_NOUN_LENGTH characters."

        else -> body.fields?.let(::fieldsProblem)
    }

    /** Whether this is given and, trimmed, empty or longer than [max] characters. */
    private fun String?.isOutOf(max: Int): Boolean = this != null && trim().length !in 1..max

    private fun fieldsProblem(fields: Map<String, Any?>): String? {
        val bytes = WowJson.mapper.writeValueAsBytes(fields).size
        return if (bytes > MAX_FIELDS_BYTES) "The wording's fields are $bytes bytes; the limit is $MAX_FIELDS_BYTES." else null
    }

    companion object {
        /** The longest record noun: a word or two, 「订单」, "order". */
        const val MAX_NOUN_LENGTH = 40

        /** The most the fields' wording may weigh, as JSON. */
        const val MAX_FIELDS_BYTES = 64 * 1024
    }
}

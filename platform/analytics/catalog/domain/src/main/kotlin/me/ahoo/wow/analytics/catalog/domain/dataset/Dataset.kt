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

import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDefinition
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDrafted
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetRevised
import me.ahoo.wow.analytics.catalog.api.dataset.DraftDataset
import me.ahoo.wow.analytics.catalog.api.dataset.ReviseDataset
import me.ahoo.wow.analytics.catalog.domain.source.SourceRejections
import me.ahoo.wow.analytics.catalog.domain.warehouse.KnownAggregates
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision

/**
 * A wide table over the catalog's sources (decisions D238 ④, D259 7.2b-2), named as its queries read it,
 * `<context>.<name>`: an analyst drafts and revises its definition, which the catalog checks against its sources
 * as they are each time ([DatasetBinder]).
 */
@AggregateRoot(lifecycle = [], ownership = Ownership.TENANT)
@Description("A wide table over the catalog's sources: its definition, its versions and where each is published")
class Dataset(private val state: DatasetState) {
    @OnCommand(produces = [DatasetDrafted::class])
    suspend fun onDraft(command: Command<DraftDataset>, sources: DatasetSources, known: KnownAggregates): Decision {
        val body = command.body
        listOf(body.context, body.name).firstOrNull { !NAME.matches(it) }?.let {
            return invalid("$it is not a dataset name: a letter, then letters, digits, _ and -.")
        }
        val id = AnalyticsCatalogContext.datasetIdOf(body.context, body.name)
        if (command.ref.id != id) {
            return invalid("The dataset ${body.name} of ${body.context} is $id, not ${command.ref.id}.")
        }
        if (known.knows(body.context, body.name)) {
            return Decision.reject(
                DatasetRejections.NAME_TAKEN,
                "${body.name} is an aggregate of ${body.context}: a dataset takes a name no aggregate has.",
            )
        }
        rejection(body.definition, sources)?.let { return it }
        return Decision.events(
            DatasetDrafted(body.context, body.name, body.definition, sourcesOf(body.definition), body.title),
        )
    }

    @OnCommand(produces = [DatasetRevised::class])
    suspend fun onRevise(command: ReviseDataset, sources: DatasetSources): Decision {
        if (command.definition == state.definition && command.title == state.title) {
            return Decision.NoOp
        }
        rejection(command.definition, sources)?.let { return it }
        return Decision.events(DatasetRevised(command.definition, sourcesOf(command.definition), command.title))
    }

    /** The rejection of [definition] when it does not hold against the catalog's [sources] as they are now. */
    private suspend fun rejection(definition: DatasetDefinition, sources: DatasetSources): Decision? {
        val states = sourcesOf(definition).associateWith { sources.source(it) }
        val result = DatasetBinder.bind(definition, states) as? DatasetBinder.Result.Invalid ?: return null
        return Decision.reject(DatasetRejections.DEFINITION_INVALID, result.problems.joinToString(" "))
    }

    private fun sourcesOf(definition: DatasetDefinition): List<String> = definition.sources.map { it.sourceId }.distinct()

    private fun invalid(message: String): Decision = Decision.reject(SourceRejections.INVALID, message)

    private companion object {
        val NAME = Regex("[A-Za-z][A-Za-z0-9_-]*")
    }
}

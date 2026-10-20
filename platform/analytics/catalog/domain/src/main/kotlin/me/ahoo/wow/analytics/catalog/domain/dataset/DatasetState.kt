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

import me.ahoo.wow.analytics.catalog.api.dataset.DatasetArtifact
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDefinition
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDrafted
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetNeedsRevision
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetPublicationRefused
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetPublicationRequested
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetPublished
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetRetired
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetRevised
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetRevisionCleared
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetRolledBack
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetStatus
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetVersion
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetViewsDropped

/**
 * A dataset of the catalog: its name, its working definition and the sources it reads, and its published [versions],
 * of which queries read the [current] one ([published]).
 */
class DatasetState {
    var context: String = ""
        private set
    var name: String = ""
        private set
    var title: String? = null
        private set
    var definition: DatasetDefinition? = null
        private set

    /** The ids of the catalog's sources the definition reads. */
    var sources: List<String> = emptyList()
        private set
    var status: DatasetStatus = DatasetStatus.DRAFT
        private set

    /** Every published version, oldest first. */
    var versions: List<DatasetVersion> = emptyList()
        private set

    /** The version queries read: 0 before the first publication. */
    var current: Int = 0
        private set

    /** The definition whose publication awaits an approver, and why it does; null when none does. */
    var requested: DatasetDefinition? = null
        private set
    var requestReasons: List<String> = emptyList()
        private set

    /** Why the published definition no longer compiles, while the dataset [DatasetStatus.NEEDS_REVISION]. */
    var problems: List<String> = emptyList()
        private set

    /** Whether a retired dataset's views were dropped. */
    var dropped: Boolean = false
        private set

    /** The version queries read, or null before the first publication. */
    fun published(): DatasetVersion? = version(current)

    /** The published [version], or null when there is none. */
    fun version(version: Int): DatasetVersion? = versions.firstOrNull { it.artifact.version == version }

    /** The version a publication creates next. */
    fun nextVersion(): Int = (versions.maxOfOrNull { it.artifact.version } ?: 0) + 1

    /** Whether the dataset exists: it was drafted. */
    val exists: Boolean get() = name.isNotEmpty()

    fun onSourcing(event: DatasetDrafted) {
        context = event.context
        name = event.name
        title = event.title
        definition = event.definition
        sources = event.sources
        status = DatasetStatus.DRAFT
    }

    fun onSourcing(event: DatasetRevised) {
        title = event.title
        definition = event.definition
        sources = event.sources
        requested = null
        requestReasons = emptyList()
    }

    fun onSourcing(event: DatasetPublished) {
        versions = versions + DatasetVersion(event.artifact, event.definition)
        current = event.artifact.version
        status = DatasetStatus.PUBLISHED
        dropped = false
        requested = null
        requestReasons = emptyList()
        problems = emptyList()
    }

    fun onSourcing(event: DatasetRolledBack) {
        current = event.version
        status = DatasetStatus.PUBLISHED
        dropped = false
        problems = emptyList()
    }

    fun onSourcing(event: DatasetNeedsRevision) {
        status = DatasetStatus.NEEDS_REVISION
        problems = event.problems
    }

    fun onSourcing(@Suppress("UNUSED_PARAMETER") event: DatasetRevisionCleared) {
        status = DatasetStatus.PUBLISHED
        problems = emptyList()
    }

    fun onSourcing(event: DatasetPublicationRequested) {
        requested = event.definition
        requestReasons = event.reasons
    }

    fun onSourcing(@Suppress("UNUSED_PARAMETER") event: DatasetPublicationRefused) {
        requested = null
        requestReasons = emptyList()
    }

    fun onSourcing(@Suppress("UNUSED_PARAMETER") event: DatasetRetired) {
        status = DatasetStatus.RETIRED
    }

    fun onSourcing(@Suppress("UNUSED_PARAMETER") event: DatasetViewsDropped) {
        dropped = true
    }

    /** The artifact queries read, or null when none is published or the dataset is retired. */
    fun serving(): DatasetArtifact? = published()?.artifact?.takeIf { status == DatasetStatus.PUBLISHED }
}

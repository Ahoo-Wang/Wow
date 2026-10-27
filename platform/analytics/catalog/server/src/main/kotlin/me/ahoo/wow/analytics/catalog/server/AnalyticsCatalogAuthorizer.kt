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

package me.ahoo.wow.analytics.catalog.server

import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.dataset.ApproveDatasetPublication
import me.ahoo.wow.analytics.catalog.api.dataset.DraftDataset
import me.ahoo.wow.analytics.catalog.api.dataset.PublishDataset
import me.ahoo.wow.analytics.catalog.api.dataset.RefuseDatasetPublication
import me.ahoo.wow.analytics.catalog.api.dataset.RetireDataset
import me.ahoo.wow.analytics.catalog.api.dataset.ReviseDataset
import me.ahoo.wow.analytics.catalog.api.dataset.RollbackDataset
import me.ahoo.wow.analytics.catalog.api.source.ApproveSensitivityChange
import me.ahoo.wow.analytics.catalog.api.source.ApproveSource
import me.ahoo.wow.analytics.catalog.api.source.DraftSource
import me.ahoo.wow.analytics.catalog.api.source.MarkSourceReviewed
import me.ahoo.wow.analytics.catalog.api.source.ReviewSourceSensitivity
import me.ahoo.wow.analytics.catalog.api.source.ReviseSource
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer

/**
 * Who sends what to the analytics catalog: every command changes what the platform's datasets are built on, so only
 * the catalog's roles send them ([AnalyticsCatalogRoles]):
 *
 * - an analyst's work, `DraftSource`, `ReviseSource`, `ReviewSourceSensitivity`, and a dataset's `DraftDataset`,
 *   `ReviseDataset`, `PublishDataset`, `RollbackDataset` and `RetireDataset`, an [AnalyticsCatalogRoles.EDITOR] or an
 *   [AnalyticsCatalogRoles.ADMIN] (analysts run their own wide tables);
 * - `MarkSourceReviewed`, an [AnalyticsCatalogRoles.APPROVER] or an [AnalyticsCatalogRoles.ADMIN];
 * - `ApproveSensitivityChange`, the privileged review of a lower protection, `ApproveSource` and a
 *   dataset's `ApproveDatasetPublication` and `RefuseDatasetPublication`, only an
 *   [AnalyticsCatalogRoles.APPROVER];
 * - every other command of the catalog (`SyncSource`, `DropDatasetViews`, `RecompileDataset`), and the framework's own system commands (`wow.*`), an
 *   [AnalyticsCatalogRoles.ADMIN].
 *
 * Where CoSec authorizes the requests itself, [policies] decides instead, by the command's type.
 */
class AnalyticsCatalogAuthorizer(private val policies: CommandAuthorizer? = null) : CommandAuthorizer {
    override suspend fun authorize(caller: Caller, action: CommandAction): Authorization {
        policies?.let { return it.authorize(caller, action) }
        val needed = ROLES[action.type.name] ?: setOf(AnalyticsCatalogRoles.ADMIN)
        return if (AnalyticsCatalogRoles.rolesOf(caller).any { it in needed }) {
            Authorization.Allowed
        } else {
            Authorization.Denied.of(
                "${caller.subject ?: "An anonymous caller"} may not ${action.type.name}: it needs one of $needed."
            )
        }
    }

    companion object {
        private val EDITORS = setOf(AnalyticsCatalogRoles.EDITOR, AnalyticsCatalogRoles.ADMIN)

        /** The roles that may send each command of the catalog, by its type's name; admin else. */
        @JvmField
        val ROLES: Map<String, Set<String>> = mapOf(
            nameOf(AnalyticsCatalogContext.SOURCE, DraftSource::class.java) to EDITORS,
            nameOf(AnalyticsCatalogContext.SOURCE, ReviseSource::class.java) to EDITORS,
            nameOf(AnalyticsCatalogContext.SOURCE, ReviewSourceSensitivity::class.java) to EDITORS,
            nameOf(AnalyticsCatalogContext.SOURCE, MarkSourceReviewed::class.java) to
                setOf(AnalyticsCatalogRoles.APPROVER, AnalyticsCatalogRoles.ADMIN),
            nameOf(AnalyticsCatalogContext.SOURCE, ApproveSensitivityChange::class.java) to
                setOf(AnalyticsCatalogRoles.APPROVER),
            nameOf(AnalyticsCatalogContext.SOURCE, ApproveSource::class.java) to setOf(AnalyticsCatalogRoles.APPROVER),
            nameOf(AnalyticsCatalogContext.DATASET, DraftDataset::class.java) to EDITORS,
            nameOf(AnalyticsCatalogContext.DATASET, ReviseDataset::class.java) to EDITORS,
            nameOf(AnalyticsCatalogContext.DATASET, PublishDataset::class.java) to EDITORS,
            nameOf(AnalyticsCatalogContext.DATASET, RollbackDataset::class.java) to EDITORS,
            nameOf(AnalyticsCatalogContext.DATASET, RetireDataset::class.java) to EDITORS,
            nameOf(AnalyticsCatalogContext.DATASET, ApproveDatasetPublication::class.java) to
                setOf(AnalyticsCatalogRoles.APPROVER),
            nameOf(AnalyticsCatalogContext.DATASET, RefuseDatasetPublication::class.java) to
                setOf(AnalyticsCatalogRoles.APPROVER),
        )

        private fun nameOf(aggregate: String, type: Class<*>): String =
            "${AnalyticsCatalogContext.NAME}.$aggregate.${type.simpleName}"
    }
}

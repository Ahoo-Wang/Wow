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

package me.ahoo.wow.query.dataset

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.MaskStrategyResolver
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QueryMask
import me.ahoo.wow.query.schema.QuerySchemaDeclaration

/**
 * Gives a dataset of [QueryDatasetLineage.SOURCE] its source's protection, as [sources] say what its table holds; a
 * dataset of another lineage is left as it is.
 *
 * - A column its source masks ([QueryDatasetSource.Masked]) is masked by the stricter of the dataset's declaration and
 *   the source's rule: the source's when the dataset declares nothing, the declaration's when it is as strict or
 *   stricter. A declaration looser than its source, by its level or by what its mask keeps, is refused with an
 *   [IllegalArgumentException] naming the source's path: a dataset has no approver, so it only tightens.
 * - A column that holds a whole document with protected values in it ([QueryDatasetSource.Opaque]) is refused: a
 *   field masks a column whole, never one path in it.
 * - A dataset whose table's source is unknown is served [unavailable][QueryDataset.unavailable], [UNRESOLVED], and
 *   the node logs why: a missing link fails closed, until its author declares `"lineage": "none"`.
 *
 * A custom mask strategy of the source is resolved by [strategies], as a snapshot read resolves it.
 */
internal class DatasetLineages(
    private val sources: QueryDatasetSources,
    private val strategies: MaskStrategyResolver,
) {
    fun resolve(dataset: QueryDataset): QueryDataset {
        when (dataset.lineage) {
            QueryDatasetLineage.NONE, QueryDatasetLineage.DECLARED -> return dataset
            // SOURCE, and a lineage this version does not know: given its source's protection, never served as is.
            else -> Unit
        }
        val source = sources.sourceOf(dataset) ?: return unresolved(dataset)
        val fields = dataset.declaration.fields.mapValues { (field, declaration) ->
            when (val column = source.columns[field.path.substringBefore('.')]) {
                null -> declaration
                is QueryDatasetSource.Masked -> inherited(dataset, field, declaration, source, column)
                // An opaque column, and a protection this version does not know, cannot be masked: refused, closed.
                else -> throw unreadable(dataset, field, source, column)
            }
        }
        return dataset.withDeclaration(QuerySchemaDeclaration(fields))
    }

    private fun inherited(
        dataset: QueryDataset,
        field: QueryField<*>,
        declaration: QueryFieldDeclaration,
        source: QueryDatasetSource,
        column: QueryDatasetSource.Masked,
    ): QueryFieldDeclaration {
        val inherited = MaskRule(column.level, column.mask, strategies)
        val declared = (declaration.maskRule as? DeclarationValue.Set)?.value
            ?: return declaration.copy(maskRule = DeclarationValue.Set(inherited))
        require(!looser(declared, inherited)) {
            "Dataset [${dataset.name}] declares [$field] ${textOf(declared)}, looser than its source " +
                "${column.path} of ${source.description}, ${textOf(inherited)}: a dataset only tightens its source's " +
                "protection. Declare it as strict, or leave it out to inherit it; loosening it is a change to the " +
                "state's @Sensitive."
        }
        return declaration
    }

    private fun unreadable(
        dataset: QueryDataset,
        field: QueryField<*>,
        source: QueryDatasetSource,
        column: QueryDatasetSource.Column,
    ): IllegalArgumentException = IllegalArgumentException(
        if (column is QueryDatasetSource.Opaque) {
            "Dataset [${dataset.name}] reads [$field], a column of ${source.description} that holds the protected " +
                "values ${column.paths}: a field masks a column whole, never one value in it. Read the value from " +
                "its own column, as the state's flat table (_state_flat) holds it."
        } else {
            "Dataset [${dataset.name}] reads [$field], a column of ${source.description} protected as $column, " +
                "which this version cannot mask: it cannot be read."
        },
    )

    private fun unresolved(dataset: QueryDataset): QueryDataset {
        logger.error {
            "Dataset [${dataset.name}] is unavailable: its backend cannot tell what its table holds " +
                "(${dataset.storage}), so it cannot mask the values a state marks sensitive. Read a state's flat " +
                "table (_state_flat) of this node, or declare \"lineage\": \"none\" if the table holds no such value."
        }
        return if (dataset.unavailable == null) dataset.withUnavailable(UNRESOLVED) else dataset
    }

    companion object {
        /** Why a dataset whose table's source is unknown is unavailable, as its queries are answered: fixed words. */
        const val UNRESOLVED: String = "source unknown: it declares \"lineage\": \"none\" if its table holds no " +
            "sensitive value"

        private val logger = KotlinLogging.logger {}

        /** Whether [declared] reveals more than [source]: a lower level, or a mask that keeps more of the value. */
        fun looser(declared: MaskRule, source: MaskRule): Boolean =
            declared.level < source.level || keepsMore(declared.mask, source.mask)

        /**
         * Whether [declared] keeps more of a value than [source]: more of either end. A custom strategy keeps what it
         * keeps, so only the same strategy, or a full mask, is known to keep no more.
         */
        private fun keepsMore(declared: QueryMask, source: QueryMask): Boolean = when {
            declared == source || declared == QueryMask.FULL -> false
            declared is QueryMask.Keep && source is QueryMask.Keep ->
                declared.prefix > source.prefix || declared.suffix > source.suffix

            else -> true
        }

        private fun textOf(rule: MaskRule): String = when (val mask = rule.mask) {
            QueryMask.FULL -> "${rule.level}, masked whole"
            is QueryMask.Keep -> "${rule.level}, keeping ${mask.prefix} first and ${mask.suffix} last characters"
            is QueryMask.Strategy -> "${rule.level}, masked by the strategy [${mask.name}]"
        }
    }
}

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

package me.ahoo.wow.datasetregistry.domain.source

import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.datasetregistry.api.source.FieldSensitivity
import me.ahoo.wow.datasetregistry.api.source.SourceColumn
import me.ahoo.wow.datasetregistry.api.source.keyOf

/**
 * The rules of a source's sensitivity (decision D241 ③): it only goes up on its own; going down takes a privileged
 * reviewer's approval.
 *
 * - [claimed] is what a `wow_catalog` row says, with each raw JSON column (`state`, `payload`, `events`) at the
 *   highest level of the values below it.
 * - [adopt] merges a row's claim into what the source gives now: a value the row raises is raised, one it keeps
 *   keeps the source's (an analyst may have tightened it), and one it lowers or no longer names keeps the source's
 *   level and is pending review.
 * - [approve] gives the pending values what the row now claims.
 */
internal object SourceSensitivity {
    /** The order of the levels: not sensitive, then [SensitivityLevel.DISPLAY], then [SensitivityLevel.CONFIDENTIAL]. */
    fun rank(level: SensitivityLevel?): Int = level?.let { it.ordinal + 1 } ?: 0

    /** [sensitive] with each raw JSON column of [columns] above a value of it, at that value's highest level. */
    fun claimed(sensitive: List<FieldSensitivity>, columns: List<SourceColumn>): List<FieldSensitivity> {
        val names = columns.mapTo(HashSet()) { it.name }
        val listed = sensitive.mapTo(HashSet()) { it.keyOf() }
        val raw = sensitive.filter { '.' in it.path }
            .groupBy { it.path.substringBefore('.') }
            .filterKeys { it in names && it !in listed }
            .map { (column, values) -> FieldSensitivity(column, values.maxBy { rank(it.level) }.level) }
        return sensitive + raw
    }

    /** What the source gives once it adopts [claimed], from [current] and the [previous] claim, and the keys pending. */
    fun adopt(
        current: List<FieldSensitivity>,
        previous: List<FieldSensitivity>,
        claimed: List<FieldSensitivity>,
        pending: List<String>,
    ): Pair<List<FieldSensitivity>, List<String>> {
        val now = current.associateBy { it.keyOf() }
        val before = previous.associateBy { it.keyOf() }
        val next = claimed.associateBy { it.keyOf() }
        val pendingNow = linkedSetOf<String>()
        val result = (now.keys + next.keys).mapNotNull { key ->
            val held = now[key]
            val claim = next[key]
            val lowered = before[key]?.let { old -> claim == null || rank(claim.level) < rank(old.level) } ?: false
            when {
                lowered -> held.also { pendingNow += key }
                claim == null -> held
                held == null || rank(claim.level) > rank(held.level) -> claim
                else -> held.also { if (key in pending && rank(claim.level) < rank(held.level)) pendingNow += key }
            }
        }
        return result to pendingNow.toList()
    }

    /** What the source gives once the [keys] pending take what [claimed] says of them; a value it names no more goes. */
    fun approve(
        current: List<FieldSensitivity>,
        claimed: List<FieldSensitivity>,
        keys: Set<String>
    ): List<FieldSensitivity> {
        val next = claimed.associateBy { it.keyOf() }
        val kept = current.filter { it.keyOf() !in keys }
        val approved = keys.mapNotNull { next[it] }
        return kept + approved
    }
}

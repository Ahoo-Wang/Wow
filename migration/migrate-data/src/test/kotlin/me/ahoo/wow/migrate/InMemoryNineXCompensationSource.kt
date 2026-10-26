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

package me.ahoo.wow.migrate

import java.util.concurrent.CopyOnWriteArrayList

/** The 9.x compensation service's executions in memory, read as the MongoDB source reads them: unresolved, by id. */
internal class InMemoryNineXCompensationSource(executions: Collection<NineXExecutionFailed> = emptyList()) :
    NineXCompensationSource {
    private val executions = CopyOnWriteArrayList(executions)

    fun add(execution: NineXExecutionFailed) {
        executions += execution
    }

    /** Replaces the execution of [execution]'s id, as 9.x does when it retries or skips one. */
    fun update(execution: NineXExecutionFailed) {
        executions.removeIf { it.id == execution.id }
        executions += execution
    }

    override fun inventory(): NineXCompensationInventory = NineXCompensationInventory(
        executions.filter { it.unresolved }
            .groupingBy { it.function to it.aggregate }
            .eachCount()
            .map { (key, count) -> NineXFailureGroup(key.first, key.second, count.toLong()) },
        executions.count { !it.unresolved }.toLong(),
    )

    override fun unresolved(after: String?, limit: Int): List<NineXExecutionFailed> =
        executions.filter { it.unresolved && (after == null || it.id > after) }.sortedBy { it.id }.take(limit)

    override fun execution(id: String): NineXExecutionFailed? = executions.lastOrNull { it.id == id }
}

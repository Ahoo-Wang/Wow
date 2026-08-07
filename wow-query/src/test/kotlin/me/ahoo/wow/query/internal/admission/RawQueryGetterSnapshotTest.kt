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

package me.ahoo.wow.query.internal.admission

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.internal.model.QueryDocumentKind
import me.ahoo.wow.query.internal.model.QueryInput
import me.ahoo.wow.query.internal.model.QueryInvocation
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryTarget
import org.junit.jupiter.api.Test

class RawQueryGetterSnapshotTest {

    private val target = QueryTarget(
        MaterializedNamedAggregate("sales", "order"),
        QueryDocumentKind.SNAPSHOT,
    )
    private val guard = RawAdmissionGuard(QueryAdmissionLimits.DEFAULT)

    @Test
    fun `should read every stream query getter exactly once`() {
        val query = SingleReadListQuery()

        val admitted = guard.admit(
            QueryInvocation(
                target,
                QueryOperation.STREAM,
                QueryResultShape.TYPED,
                QueryInput.Stream(query),
            ),
        )

        query.reads.assert().containsExactlyInAnyOrderEntriesOf(
            mapOf("condition" to 1, "projection" to 1, "sort" to 1, "limit" to 1),
        )
        (admitted.input as AdmittedQueryInput.Stream).limit.assert().isEqualTo(10)
    }

    @Test
    fun `should read every page query getter exactly once`() {
        val query = SingleReadPagedQuery()

        val admitted = guard.admit(
            QueryInvocation(
                target,
                QueryOperation.PAGE,
                QueryResultShape.DYNAMIC,
                QueryInput.Page(query),
            ),
        )

        query.reads.assert().containsExactlyInAnyOrderEntriesOf(
            mapOf("condition" to 1, "projection" to 1, "sort" to 1, "pagination" to 1),
        )
        (admitted.input as AdmittedQueryInput.Page).page.assert().isEqualTo(AdmittedPage(2, 25, 25))
    }

    private abstract class SingleReadQueryable {
        val reads: MutableMap<String, Int> = linkedMapOf()

        protected fun <T> readOnce(name: String, value: T): T {
            val count = reads.getOrDefault(name, 0) + 1
            reads[name] = count
            check(count == 1) {
                "$name can only be read once."
            }
            return value
        }
    }

    private class SingleReadListQuery : SingleReadQueryable(), IListQuery {
        override val condition: Condition
            get() = readOnce("condition", Condition.ALL)
        override val projection: Projection
            get() = readOnce("projection", Projection.ALL)
        override val sort: List<Sort>
            get() = readOnce("sort", emptyList())
        override val limit: Int
            get() = readOnce("limit", 10)

        override fun withCondition(newCondition: Condition): IListQuery = this

        override fun withProjection(newProjection: Projection): IListQuery = this
    }

    private class SingleReadPagedQuery : SingleReadQueryable(), IPagedQuery {
        override val condition: Condition
            get() = readOnce("condition", Condition.ALL)
        override val projection: Projection
            get() = readOnce("projection", Projection.ALL)
        override val sort: List<Sort>
            get() = readOnce("sort", emptyList())
        override val pagination: Pagination
            get() = readOnce("pagination", Pagination(2, 25))

        override fun withCondition(newCondition: Condition): IPagedQuery = this

        override fun withProjection(newProjection: Projection): IPagedQuery = this
    }
}

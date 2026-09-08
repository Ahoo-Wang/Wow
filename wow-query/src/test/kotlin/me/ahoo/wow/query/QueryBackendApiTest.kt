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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.query.schema.QueryModelSchema
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class QueryBackendApiTest {
    @Test
    fun `backend should accept logical query and schema as separate parameters`() {
        val operations = mapOf(
            "single" to ISingleQuery::class.java,
            "list" to IListQuery::class.java,
            "paged" to IPagedQuery::class.java,
            "cursor" to ICursorQuery::class.java,
            "count" to FilterExpression::class.java,
            "aggregate" to AggregationQuery::class.java,
        )
        QueryBackend::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .assert().hasSize(operations.size)
        operations.forEach { (operation, queryType) ->
            val method = QueryBackend::class.java.getMethod(operation, queryType, QueryModelSchema::class.java)
            method.parameterTypes.assert().containsExactly(queryType, QueryModelSchema::class.java)
            Modifier.isAbstract(method.modifiers).assert().isTrue()
        }
    }

    @Test
    fun `cursor should remain a required backend contract`() {
        Modifier.isAbstract(
            QueryBackend::class.java.getMethod(
                "cursor",
                ICursorQuery::class.java,
                QueryModelSchema::class.java
            ).modifiers,
        ).assert().isTrue()
    }
}

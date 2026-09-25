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
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.Queryable
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.WildcardType

class QueryBackendApiTest {
    @Test
    fun `backend primitives accept only an admitted query`() {
        val primitives = mapOf(
            "stream" to (IListQuery::class.java to emptyList<Class<*>>()),
            "page" to (Queryable::class.java to listOf(PageWindow::class.java)),
            "count" to (FilterExpression::class.java to emptyList()),
            "aggregate" to (AggregationQuery::class.java to listOf(GroupWindow::class.java)),
        )
        QueryBackend::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .assert().containsExactlyInAnyOrder(*(primitives.keys + "getCursorPositions").toTypedArray())
        primitives.forEach { (primitive, signature) ->
            val (queryType, windows) = signature
            val method = QueryBackend::class.java.getMethod(
                primitive,
                AdmittedQuery::class.java,
                *windows.toTypedArray()
            )
            Modifier.isAbstract(method.modifiers).assert().isTrue()
            val admitted = method.genericParameterTypes.first() as ParameterizedType
            admitted.rawType.assert().isEqualTo(AdmittedQuery::class.java)
            val argument = admitted.actualTypeArguments.single()
            val bound = if (argument is WildcardType) argument.upperBounds.single() else argument
            val raw = if (bound is ParameterizedType) bound.rawType else bound
            raw.assert().isEqualTo(queryType)
        }
    }
}

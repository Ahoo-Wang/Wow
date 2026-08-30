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

package me.ahoo.wow.compensation.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.compensation.domain.ExecutionFailed
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.schema.query.JsonQuerySchemaSource
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class ExecutionFailedQuerySchemaTest {
    @Test
    fun `should expose executeAt as epoch milliseconds to snapshot queries`() {
        val context = QuerySchemaContext(
            ExecutionFailed::class.java.aggregateMetadata<Any, Any>().namedAggregate,
            QueryModel.SNAPSHOT,
        )

        val declaration = JsonQuerySchemaSource().load(context).single().block()!!

        declaration.fields.getValue(LogicalField("state.executeAt")).semanticType.assert()
            .isEqualTo(DeclarationValue.Set(Temporal.Epoch(TimeUnit.MILLISECONDS)))
    }
}

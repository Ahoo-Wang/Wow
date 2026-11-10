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

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.QueryValueKind

/** What the merger's tests share: a merger per test, the system declaration, an opaque and a string declaration. */
internal abstract class QuerySchemaMergerFixtures {
    protected val merger = QuerySchemaMerger()

    /** An opaque value, as the Manifest states a JSON-typed property: an unknown kind and no value type. */
    protected val opaque = QueryFieldDeclaration(
        kind = DeclarationValue.Set(QueryValueKind.UNKNOWN),
        valueTypes = DeclarationValue.Set(emptySet()),
    )

    protected fun string() = QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)))

    protected fun system(): QuerySchemaDeclaration =
        SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT)
}

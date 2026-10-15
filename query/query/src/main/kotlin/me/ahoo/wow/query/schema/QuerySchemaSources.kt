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

/**
 * The declarations registered in code ([querySchemaRegistration]), each for the model its context names. 9.x's
 * classpath and working-directory declaration files are gone: the Manifest ([ManifestQuerySchemaSource]) states what they supplemented.
 */
class BeanQuerySchemaSource(
    registrations: List<QuerySchemaRegistration>,
) : QuerySchemaSource {
    private val registrations = registrations.toList()

    override val priority: Int = QuerySchemaSourcePriority.BEAN

    override suspend fun load(context: QuerySchemaContext): List<QuerySchemaDeclaration> =
        registrations.filter { it.context == context }.map(QuerySchemaRegistration::declaration)
}

/** The system fields of [QuerySchemaContext.model]'s profile: the source every built-in model starts from. */
internal object SystemQuerySchemaSource : QuerySchemaSource {
    override val priority: Int = Int.MIN_VALUE

    override suspend fun load(context: QuerySchemaContext): List<QuerySchemaDeclaration> =
        listOf(declaration(context.model))

    fun declaration(model: QueryModel): QuerySchemaDeclaration = QueryModelProfile.of(model)?.systemDeclaration
        ?: throw QuerySchemaValidationException("Unsupported System query model: [$model].")
}

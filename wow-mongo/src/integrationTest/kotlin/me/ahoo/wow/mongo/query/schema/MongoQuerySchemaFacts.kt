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

package me.ahoo.wow.mongo.query.schema

import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryModelSchema
import reactor.core.publisher.Mono
import org.bson.Document

/** The schema the Catalog compiles from the facts a collection's [indexes] and [validatorSchema] report. */
internal fun MongoQuerySchemaAdapter.Companion.bind(
    logicalSchema: LogicalQuerySchema,
    indexes: List<Document>,
    validatorSchema: Document?,
    model: QueryModel = QueryModel.SNAPSHOT,
): QueryModelSchema = facts(logicalSchema, indexes, validatorSchema, model).compile(model, logicalSchema)

/** The schema the Catalog compiles from the facts this adapter loads for [logicalSchema]'s [model]. */
internal fun MongoQuerySchemaAdapter.resolve(
    logicalSchema: LogicalQuerySchema,
    model: QueryModel = QueryModel.SNAPSHOT,
): Mono<QueryModelSchema> = facts(logicalSchema).map { it.compile(model, logicalSchema) }

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

package me.ahoo.wow.query.snapshot

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.query.AbstractQueryGateway
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryLogErrorHandler
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import tools.jackson.databind.JavaType

interface SnapshotQueryGateway<S : Any> : QueryGateway<MaterializedSnapshot<S>>

class DefaultSnapshotQueryGateway<S : Any>(
    namedAggregate: NamedAggregate,
    backend: SnapshotQueryBackend,
    schemaProvider: QueryModelSchemaProvider,
    validationMode: QuerySchemaValidationMode,
    targetType: JavaType,
    filters: List<QueryFilter<QueryContext<*, *>>> = emptyList(),
    errorHandler: ErrorHandler<QueryContext<*, *>> = QueryLogErrorHandler(),
) : SnapshotQueryGateway<S>,
    AbstractQueryGateway<MaterializedSnapshot<S>>(
        namedAggregate,
        backend,
        schemaProvider,
        validationMode,
        targetType,
        filters,
        SnapshotQueryGateway::class,
        errorHandler,
    )

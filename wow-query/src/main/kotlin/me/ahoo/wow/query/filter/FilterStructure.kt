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

package me.ahoo.wow.query.filter

import me.ahoo.wow.api.query.FieldPredicate
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.spec.spec

/**
 * The field a predicate names, or `null` for filters that name none (logical, system-field, match-all/none and
 * search filters, whose fields are a set). Pairs with [me.ahoo.wow.api.query.spec.FilterOperatorSpec]: the spec
 * says what the operator needs, [FieldPredicate] says where the node keeps its field.
 */
fun FilterExpression.predicateField(): QueryField? = (this as? FieldPredicate)?.field

/** The field capability this node's operator requires, per its [me.ahoo.wow.api.query.spec.FilterOperatorSpec]. */
fun FilterExpression.requiredCapability(): QueryCapability =
    checkNotNull(spec.requiredCapability(this)) { "Filter [$operator] requires no field capability." }

/**
 * This predicate naming [field] instead of its [predicateField]; filters that name no field are returned unchanged.
 * The inverse of [predicateField], used to rewrite a query's field references.
 */
fun FilterExpression.withPredicateField(field: QueryField): FilterExpression =
    (this as? FieldPredicate)?.withField(field) ?: this

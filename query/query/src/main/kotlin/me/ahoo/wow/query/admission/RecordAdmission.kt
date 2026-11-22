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

package me.ahoo.wow.query.admission

import me.ahoo.wow.query.mask.SchemaMasker
import me.ahoo.wow.query.schema.QueryModelSchema
import tools.jackson.databind.node.ObjectNode

/**
 * Masks [record], a record of this schema's model, by the schema's response masks, in place: every sensitive value,
 * whatever its level, is replaced by its mask, whoever the caller is (the level decides what a query may do with the
 * raw value), except a `DISPLAY` value of a query that reveals them ([revealsDisplay],
 * [me.ahoo.wow.query.QueryCaller.revealsDisplay]), which is returned as it is.
 */
internal fun QueryModelSchema.maskRecord(
    record: ObjectNode,
    revealsDisplay: Boolean = false,
): ObjectNode = SchemaMasker.create(this, revealsDisplay)?.mask(record) ?: record

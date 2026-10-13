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

package me.ahoo.wow.mongo

import me.ahoo.wow.api.caller.Operator
import org.bson.Document

/**
 * An operator (decision D163) as the MongoDB stores keep it: the sub-document `{id, tenantId, crossScope}`, the shape
 * of the snapshot query record's `operator` field, so a snapshot query reads `operator.id` where it is stored. The
 * log, the snapshots and the timers write it alike.
 */
internal object MongoOperators {
    const val ID = "id"
    const val TENANT_ID = "tenantId"
    const val CROSS_SCOPE = "crossScope"

    /** [operator] as its sub-document; null for none. */
    fun document(operator: Operator?): Document? = operator?.let {
        Document(ID, it.id).append(TENANT_ID, it.tenantId).append(CROSS_SCOPE, it.crossScope)
    }

    /** The operator [document] holds; null for none, as a document written before the field has. */
    fun of(document: Document?): Operator? = document?.let {
        Operator(it.getString(ID), it.getString(TENANT_ID), it.getBoolean(CROSS_SCOPE, false))
    }
}

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

import com.mongodb.ErrorCategory
import com.mongodb.MongoException

/** Whether [this] reports a unique index violation, as a write error or as a command error (findAndModify). */
internal fun MongoException.isDuplicateKey(): Boolean =
    ErrorCategory.fromErrorCode(code) == ErrorCategory.DUPLICATE_KEY

/**
 * Runs a conditional upsert: one single-document write whose filter carries a guard (a version, a position, an
 * expiry, an owner) besides the key, so it updates the stored document only when the guard holds, and inserts one
 * when the key is absent.
 *
 * When the key exists but the guard fails, the upsert tries to insert and the unique key index refuses it. The same
 * happens when a concurrent writer inserted the key between this write's match and its insert, and then the guard
 * may well hold against the document that won. So on a duplicate key [write] runs once more without upsert: the key
 * exists by then (the V10 stores remove a document only on an explicit delete or release), and the update applies
 * exactly when the guard holds against the latest document. MongoDB re-evaluates a single-document write's filter
 * after a write conflict, so of concurrent guarded updates only those whose guard still holds apply.
 */
internal inline fun <T> conditionalUpsert(write: (upsert: Boolean) -> T): T =
    try {
        write(true)
    } catch (failure: MongoException) {
        if (!failure.isDuplicateKey()) {
            throw failure
        }
        write(false)
    }

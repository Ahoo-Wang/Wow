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

import com.mongodb.MongoBulkWriteException
import com.mongodb.MongoCommandException
import com.mongodb.MongoWriteConcernException
import com.mongodb.MongoWriteException
import com.mongodb.WriteError
import me.ahoo.wow.spi.subscription.FailureDescriber
import me.ahoo.wow.spi.subscription.FailureDescription
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.code
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.name
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.phrase

/** The words of MongoDB's descriptions. */
internal enum class MongoPhrase {
    WRITE_ERROR,
    WRITE_CONCERN_ERROR,
    COMMAND_FAILED,
    CODE,
}

/**
 * The MongoDB driver's failures an application's handler meets on its own client, a projection writing its read model,
 * say, described for the reason a parked aggregate records: a write's error by its code and the driver's category
 * (`write error code 11000 duplicate key`), a command's and a write concern's by their code and the server's name of
 * it (`DuplicateKey`). Never the server's message, which quotes the key that collided (`dup key: { email: "…" }`).
 *
 * Found by `ServiceLoader`, from this module's `META-INF/services/me.ahoo.wow.spi.subscription.FailureDescriber`.
 */
internal class MongoFailureDescriber : FailureDescriber {
    override fun describe(failure: Throwable): FailureDescription? = when (failure) {
        is MongoWriteException -> writeError(failure.error)
        is MongoBulkWriteException -> failure.writeErrors.firstOrNull()?.let(::writeError)
        is MongoWriteConcernException -> coded(
            MongoPhrase.WRITE_CONCERN_ERROR,
            failure.writeConcernError.code,
            failure.writeConcernError.codeName,
        )

        is MongoCommandException -> coded(MongoPhrase.COMMAND_FAILED, failure.errorCode, failure.errorCodeName)
        else -> null
    }

    private fun writeError(error: WriteError): FailureDescription = FailureDescription.of(
        phrase(MongoPhrase.WRITE_ERROR),
        phrase(MongoPhrase.CODE),
        code(error.code.toLong()),
        phrase(error.category),
    )

    /** [what] with [code], and the server's name of it when it gives one a description takes. */
    private fun coded(what: MongoPhrase, code: Int, codeName: String?): FailureDescription {
        val named = codeName?.takeIf { it.isNotEmpty() }?.let { runCatching { name(it) }.getOrNull() }
        val parts = listOfNotNull(phrase(what), phrase(MongoPhrase.CODE), code(code.toLong()), named)
        return FailureDescription.of(*parts.toTypedArray())
    }
}

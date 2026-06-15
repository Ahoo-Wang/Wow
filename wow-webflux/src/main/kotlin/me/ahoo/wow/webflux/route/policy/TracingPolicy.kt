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

package me.ahoo.wow.webflux.route.policy

import org.springframework.web.reactive.function.server.ServerRequest

data class TracingRange(
    val replayHeadVersion: Int,
    val emitHeadVersion: Int,
    val tailVersion: Int
)

internal data class TracingRequest(
    val headVersion: Int?,
    val tailVersion: Int?,
    val limit: Int?
) {
    val emitHeadVersion: Int
        get() = headVersion ?: TracingPolicy.DEFAULT_HEAD_VERSION

    val hasLimit: Boolean
        get() = limit != null

    fun toRange(totalVersion: Int): TracingRange {
        val effectiveTailVersion = if (totalVersion <= TracingPolicy.EMPTY_TAIL_VERSION) {
            TracingPolicy.EMPTY_TAIL_VERSION
        } else {
            tailVersion?.coerceAtMost(totalVersion) ?: totalVersion
        }
        val effectiveEmitHeadVersion = limit?.let {
            val limitedHeadVersion = effectiveTailVersion - it + 1
            maxOf(emitHeadVersion, limitedHeadVersion)
        } ?: emitHeadVersion

        return TracingRange(
            replayHeadVersion = TracingPolicy.DEFAULT_HEAD_VERSION,
            emitHeadVersion = effectiveEmitHeadVersion,
            tailVersion = effectiveTailVersion,
        )
    }
}

class TracingPolicy {

    fun range(request: ServerRequest, totalVersion: Int): TracingRange {
        return request(request).toRange(totalVersion)
    }

    internal fun request(request: ServerRequest): TracingRequest {
        val headVersion = request.queryInt(HEAD_VERSION)
        val tailVersion = request.queryInt(TAIL_VERSION)
        val limit = request.queryInt(LIMIT)

        headVersion?.let {
            require(it > 0) {
                "$HEAD_VERSION must be greater than 0."
            }
        }
        limit?.let {
            require(it >= 0) {
                "$LIMIT must be greater than or equal to 0."
            }
        }

        val emitHeadVersion = headVersion ?: DEFAULT_HEAD_VERSION
        tailVersion?.let {
            require(it >= emitHeadVersion) {
                "$TAIL_VERSION must be greater than or equal to $HEAD_VERSION."
            }
        }

        return TracingRequest(
            headVersion = headVersion,
            tailVersion = tailVersion,
            limit = limit,
        )
    }

    private fun ServerRequest.queryInt(name: String): Int? {
        return queryParam(name)
            .map {
                require(it.isNotBlank()) {
                    "$name must not be blank."
                }
                it.toIntOrNull() ?: throw IllegalArgumentException("$name must be an integer.")
            }
            .orElse(null)
    }

    companion object {
        const val HEAD_VERSION: String = "headVersion"
        const val TAIL_VERSION: String = "tailVersion"
        const val LIMIT: String = "limit"
        const val DEFAULT_HEAD_VERSION: Int = 1
        const val EMPTY_TAIL_VERSION: Int = 0
    }
}

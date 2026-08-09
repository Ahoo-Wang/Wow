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

package me.ahoo.wow.query.internal.policy

import me.ahoo.wow.query.internal.model.QueryExecutionMode
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejection
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant
import java.util.Collections

internal class QueryPurpose(val value: String) {
    init {
        requireValidIdentifier(value, "Query purpose")
    }

    override fun equals(other: Any?): Boolean = this === other || other is QueryPurpose && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

internal class LegacyQueryCallerId(val value: String) {
    init {
        requireValidIdentifier(value, "Legacy query caller id")
    }

    override fun equals(other: Any?): Boolean = this === other || other is LegacyQueryCallerId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

internal sealed interface QueryOwnerGrant {
    data object Unrestricted : QueryOwnerGrant

    data class Only(val ownerId: String) : QueryOwnerGrant {
        init {
            requireValidIdentifier(ownerId, "Authority owner id")
        }
    }
}

internal sealed interface QuerySpaceGrant {
    data object Unrestricted : QuerySpaceGrant

    data object DenyAll : QuerySpaceGrant

    class AllowList(spaceIds: Iterable<String>) : QuerySpaceGrant {
        val spaceIds: Set<String> = immutableIdentifiers(spaceIds.toSet(), "Authority space id")

        init {
            require(this.spaceIds.isNotEmpty()) {
                "Authority space allow-list must not be empty."
            }
        }

        override fun equals(other: Any?): Boolean = this === other || other is AllowList && spaceIds == other.spaceIds

        override fun hashCode(): Int = spaceIds.hashCode()
    }
}

internal sealed interface QueryAuthority {
    val principalId: String

    class Subject(
        val subjectId: String,
        val tenantId: String,
        val ownerGrant: QueryOwnerGrant,
        val spaceGrant: QuerySpaceGrant,
    ) : QueryAuthority {
        override val principalId: String = subjectId

        init {
            requireValidIdentifier(subjectId, "Authority principal id")
            requireValidIdentifier(tenantId, "Authority tenant id")
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                other is Subject &&
                subjectId == other.subjectId &&
                tenantId == other.tenantId &&
                ownerGrant == other.ownerGrant &&
                spaceGrant == other.spaceGrant

        override fun hashCode(): Int {
            var result = subjectId.hashCode()
            result = 31 * result + tenantId.hashCode()
            result = 31 * result + ownerGrant.hashCode()
            result = 31 * result + spaceGrant.hashCode()
            return result
        }
    }

    class Service(
        val serviceId: String,
        val tenantId: String,
        purposes: Set<QueryPurpose>,
    ) : QueryAuthority {
        override val principalId: String = serviceId
        val purposes: Set<QueryPurpose> = Collections.unmodifiableSet(
            LinkedHashSet(purposes.sortedBy(QueryPurpose::value)),
        )

        init {
            requireValidIdentifier(serviceId, "Authority principal id")
            requireValidIdentifier(tenantId, "Authority tenant id")
            require(purposes.isNotEmpty()) {
                "Service authority purposes must not be empty."
            }
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                other is Service &&
                serviceId == other.serviceId &&
                tenantId == other.tenantId &&
                purposes == other.purposes

        override fun hashCode(): Int {
            var result = serviceId.hashCode()
            result = 31 * result + tenantId.hashCode()
            result = 31 * result + purposes.hashCode()
            return result
        }
    }

    data class System(
        override val principalId: String,
        val justification: String,
    ) : QueryAuthority {
        init {
            requireValidIdentifier(principalId, "Authority principal id")
            requireValidIdentifier(justification, "System authority justification")
        }
    }

    data class Legacy(val grant: LegacyQueryGrant) : QueryAuthority {
        override val principalId: String = grant.callerId.value
    }
}

internal data class QueryResourceScope(
    val tenantId: String? = null,
    val ownerId: String? = null,
    val spaceId: String? = null,
) {
    init {
        tenantId?.let { requireValidIdentifier(it, "Resource tenant id") }
        ownerId?.let { requireValidIdentifier(it, "Resource owner id") }
        spaceId?.let { requireValidIdentifier(it, "Resource space id") }
    }
}

internal data class QueryExecutionBudget(
    val maxScannedRecords: Long? = null,
    val maxReturnedRecords: Long? = null,
    val maxPageWindow: Long? = null,
    val maxCandidateBuckets: Int? = null,
    val maxReturnedBuckets: Int? = null,
    val maxCursorPages: Int? = null,
    val allowDiskUse: Boolean = false,
) {
    init {
        require(maxScannedRecords == null || maxScannedRecords > 0)
        require(maxReturnedRecords == null || maxReturnedRecords > 0)
        require(maxPageWindow == null || maxPageWindow > 0)
        require(maxCandidateBuckets == null || maxCandidateBuckets > 0)
        require(maxReturnedBuckets == null || maxReturnedBuckets > 0)
        require(maxCursorPages == null || maxCursorPages > 0)
    }
}

internal data class QueryExecutionRequest(
    val target: QueryTarget,
    val purpose: QueryPurpose,
    val executionMode: QueryExecutionMode,
    val validationMode: QueryValidationMode,
    val resourceScope: QueryResourceScope = QueryResourceScope(),
    val deadline: Instant? = null,
    val budget: QueryExecutionBudget = QueryExecutionBudget(),
)

internal data class LegacyQueryGrant(
    val callerId: LegacyQueryCallerId,
    val target: QueryTarget,
    val purpose: QueryPurpose,
    val executionMode: QueryExecutionMode,
    val resourceScope: QueryResourceScope,
)

internal data class QueryExecutionContext(
    val target: QueryTarget,
    val purpose: QueryPurpose,
    val authority: QueryAuthority,
    val executionMode: QueryExecutionMode,
    val validationMode: QueryValidationMode,
    val resourceScope: QueryResourceScope,
    val deadline: Instant?,
    val budget: QueryExecutionBudget,
)

internal fun interface QueryAuthorityProvider {
    fun resolve(request: QueryExecutionRequest): Mono<QueryAuthority>
}

internal class LegacyQueryAuthorityProvider(
    private val grant: LegacyQueryGrant? = null,
) : QueryAuthorityProvider {
    override fun resolve(request: QueryExecutionRequest): Mono<QueryAuthority> = Mono.defer {
        val configuredGrant = grant
        if (configuredGrant == null || !request.matches(configuredGrant)) {
            return@defer Mono.error(LegacyGrantRejectedException())
        }
        Mono.just(QueryAuthority.Legacy(configuredGrant))
    }
}

internal class QueryExecutionContextFactory(
    private val authorityProvider: QueryAuthorityProvider,
    private val clock: Clock,
) {
    fun resolve(request: QueryExecutionRequest): Mono<QueryExecutionContext> = Mono.defer {
        if (request.deadline?.isAfter(clock.instant()) == false) {
            return@defer Mono.error(
                rejectedException(
                    QueryRejectionCategory.BUDGET_EXCEEDED,
                    EXECUTION_CONTEXT_PATH.property("deadline"),
                    QueryRejectionCode.DEADLINE_EXPIRED,
                ),
            )
        }
        resolveAuthority(request)
            .switchIfEmpty(
                Mono.error(
                    rejectedException(
                        QueryRejectionCategory.ACCESS_DENIED,
                        EXECUTION_CONTEXT_PATH.property("authority"),
                        QueryRejectionCode.AUTHORITY_REQUIRED,
                    ),
                ),
            )
            .map { authority -> request.toExecutionContext(authority) }
    }

    private fun resolveAuthority(request: QueryExecutionRequest): Mono<QueryAuthority> =
        Mono.defer { authorityProvider.resolve(request) }
            .onErrorMap { error -> error.toAuthorityRejection() }

    private fun Throwable.toAuthorityRejection(): QueryRejectedException =
        when (this) {
            is LegacyGrantRejectedException -> rejectedException(
                QueryRejectionCategory.ACCESS_DENIED,
                EXECUTION_CONTEXT_PATH.property("legacyGrant"),
                QueryRejectionCode.LEGACY_CALLER_NOT_ALLOWED,
                this,
            )

            is TrustedAuthorityRejectedException -> rejectedException(
                QueryRejectionCategory.ACCESS_DENIED,
                path,
                code,
                this,
            )

            else -> rejectedException(
                QueryRejectionCategory.ACCESS_DENIED,
                EXECUTION_CONTEXT_PATH.property("authority"),
                QueryRejectionCode.AUTHORITY_RESOLUTION_FAILED,
                this,
            )
        }

    private fun QueryExecutionRequest.toExecutionContext(authority: QueryAuthority): QueryExecutionContext {
        if (authority is QueryAuthority.Legacy && !matches(authority.grant)) {
            throw rejectedException(
                QueryRejectionCategory.ACCESS_DENIED,
                EXECUTION_CONTEXT_PATH.property("legacyGrant"),
                QueryRejectionCode.LEGACY_CALLER_NOT_ALLOWED,
            )
        }
        if (deadline?.isAfter(clock.instant()) == false) {
            throw rejectedException(
                QueryRejectionCategory.BUDGET_EXCEEDED,
                EXECUTION_CONTEXT_PATH.property("deadline"),
                QueryRejectionCode.DEADLINE_EXPIRED,
            )
        }
        return QueryExecutionContext(
            target,
            purpose,
            authority,
            executionMode,
            validationMode,
            resourceScope,
            deadline,
            budget,
        )
    }
}

private val EXECUTION_CONTEXT_PATH = QueryRejectionPath.ROOT.property("executionContext")

private class LegacyGrantRejectedException : IllegalStateException("Legacy query grant rejected.")

internal class TrustedAuthorityRejectedException(
    val path: QueryRejectionPath,
    val code: QueryRejectionCode,
    cause: Throwable,
) : IllegalStateException("Trusted authority rejected.", cause)

private fun QueryExecutionRequest.matches(grant: LegacyQueryGrant): Boolean =
    executionMode == grant.executionMode &&
        target == grant.target &&
        purpose == grant.purpose &&
        resourceScope == grant.resourceScope

internal fun rejectedException(
    category: QueryRejectionCategory,
    path: QueryRejectionPath,
    code: QueryRejectionCode,
    cause: Throwable? = null,
): QueryRejectedException = QueryRejectedException(QueryRejection(category, path, code), cause)

private fun immutableIdentifiers(values: Set<String>, label: String): Set<String> {
    values.forEach { requireValidIdentifier(it, label) }
    return Collections.unmodifiableSet(LinkedHashSet(values.sorted()))
}

private fun requireValidIdentifier(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_IDENTIFIER_LENGTH && value.none(Char::isISOControl)) {
        "$label must not be blank, exceed $MAX_IDENTIFIER_LENGTH characters or contain control characters."
    }
}

private const val MAX_IDENTIFIER_LENGTH = 512

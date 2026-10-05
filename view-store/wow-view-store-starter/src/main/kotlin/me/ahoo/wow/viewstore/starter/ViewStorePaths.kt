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

package me.ahoo.wow.viewstore.starter

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.ScopeIds
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

/**
 * Where the view store's routes live. Wow prefixes an aggregate's routes with its context's alias when the host's
 * own context is another, so a host (and the standalone server, whose context is not `view-store`) serves them under
 * `/view-store`; the custom routes follow the same rule.
 */
internal class ViewStorePaths(currentContext: NamedBoundedContext) {
    companion object {
        const val TENANT_ID = MessageRecords.TENANT_ID
        const val OWNER_ID = MessageRecords.OWNER_ID
        const val DEFINITION_ID = "definitionId"
        const val REQUEST_ID = "requestId"
        const val ID = MessageRecords.ID
    }

    /** `/view-store`, or empty when the host's own context is the view store's. */
    val prefix: String = if (currentContext.contextName == ViewStoreService.SERVICE_NAME) {
        ""
    } else {
        "/${ViewStoreService.SERVICE_ALIAS}"
    }

    /** The tenant and owner every route starts with. */
    val scope: String = "$prefix/tenant/{$TENANT_ID}/owner/{$OWNER_ID}"

    val systemViews: String = "$scope/system-views"
    val systemView: String = "$systemViews/{$ID}"
    val preferences: String = "$scope/definitions/{$DEFINITION_ID}/preferences"
    val replay: String = "$scope/${ViewStoreService.VIEW_AGGREGATE_NAME}/requests/{$REQUEST_ID}"

    /** Wow's route of `ShareView`, which the starter answers first (see [ViewAudienceHandlers.share]). */
    val share: String = "$scope/${ViewStoreService.VIEW_AGGREGATE_NAME}/{$ID}/share"

    /** The view store's route of `ClaimView` (see [ViewAudienceHandlers.claim]). */
    val claim: String = "$scope/${ViewStoreService.VIEW_AGGREGATE_NAME}/{$ID}/claim"

    /*
     * Every decision on a request's path is made the way Spring routes it: with a PathPattern on the path's segments,
     * decoded and without `;` parameters. A regex or string comparison on the raw path would let `/view-store;x`,
     * `/view%2Dstore` or `/%6Fwner` reach the view store's routes while skipping these checks.
     */
    private val scopePattern: PathPattern = "$scope/**".toPattern()
    private val viewPattern: PathPattern = "$scope/${ViewStoreService.VIEW_AGGREGATE_NAME}/{$ID}/**".toPattern()

    /** Whether [path] is one of the view store's tenant-and-owner routes. */
    fun isViewStorePath(path: PathContainer): Boolean = scopePattern.matches(path)

    fun isViewStorePath(path: String): Boolean = isViewStorePath(PathContainer.parsePath(path))

    /**
     * Whether the decoded tenant and owner of [path] (one of the view store's paths) name one ([ScopeIds]): not
     * empty, and nothing invisible in them. Since 9.3.0 Wow refuses a blank identity path segment on its own routes
     * (before, it read one as missing and fell back to the headers); this rule also covers the starter's routes, the
     * invisible characters Wow accepts, and answers with the view store's error code.
     */
    fun hasValidScope(path: PathContainer): Boolean {
        val variables = scopePattern.matchAndExtract(path)?.uriVariables ?: return false
        return variables[TENANT_ID].isScopeId() && variables[OWNER_ID].isScopeId()
    }

    fun hasValidScope(path: String): Boolean = hasValidScope(PathContainer.parsePath(path))

    /** The path every request on stored system views starts with, spelled as a gateway rule names it. */
    val systemScope: String =
        "$prefix/tenant/${ViewStoreService.SYSTEM_TENANT_ID}/owner/${ViewStoreService.SYSTEM_OWNER_ID}"

    /**
     * Whether [path] decodes to the tenant `(platform)` and the owner `(system)` in any letter case: a request on
     * stored system views, or one that would be taken for it by a gateway matching without regard to case.
     */
    fun isSystemScope(path: PathContainer): Boolean {
        val variables = scopePattern.matchAndExtract(path)?.uriVariables ?: return false
        return variables[TENANT_ID].equals(ViewStoreService.SYSTEM_TENANT_ID, ignoreCase = true) &&
            variables[OWNER_ID].equals(ViewStoreService.SYSTEM_OWNER_ID, ignoreCase = true)
    }

    /**
     * Whether [path], one of [isSystemScope], spells [systemScope] literally: no percent-encoding, no `;` parameter,
     * no other letter case. A gateway rule that admits writes to system views by their path then sees every one of
     * them, whether the gateway matches the raw or the decoded path.
     */
    fun spellsSystemScope(path: PathContainer): Boolean = path.value().startsWith("$systemScope/")

    /** Whether [path] addresses the owner `(system)` in any tenant, decoded. */
    fun isSystemOwner(path: PathContainer): Boolean =
        scopePattern.matchAndExtract(path)?.uriVariables?.get(OWNER_ID) == ViewStoreService.SYSTEM_OWNER_ID

    /** The tenant and id [path] addresses a view by, decoded, or `null` when it addresses none. */
    fun viewTarget(path: PathContainer): ViewTarget? {
        val variables = viewPattern.matchAndExtract(path)?.uriVariables ?: return null
        return ViewTarget(tenantId = variables.getValue(TENANT_ID), viewId = variables.getValue(ID))
    }

    fun viewTarget(path: String): ViewTarget? = viewTarget(PathContainer.parsePath(path))
}

private fun String?.isScopeId(): Boolean = ScopeIds.isValid(this)

/**
 * The parser of every path decision of the starter. It matches case-insensitively: a host may configure its WebFlux
 * path matching so (`PathMatchConfigurer.setUseCaseSensitiveMatch(false)`), and Spring then routes Wow's routes
 * case-insensitively too. The decisions that refuse (closed routes, the facade, the scope rule) must match at least
 * what Spring routes, and matching more is safe: on a case-sensitive host a path whose case differs from a route's
 * reaches none of the view store's routes, so what the starter decides for it has no effect — except where a variable
 * of a closed route takes the segment in that case: the open routes are therefore matched exactly
 * ([ViewStoreRouteGuard.isClosed]).
 */
private val PATH_PARSER = PathPatternParser().apply { isCaseSensitive = false }

internal fun String.toPattern(): PathPattern = PATH_PARSER.parse(this)

/** Spring's default, case-sensitive parser: for the open routes, which win over a closed route only exactly. */
private val EXACT_PATH_PARSER = PathPatternParser()

internal fun String.toExactPattern(): PathPattern = EXACT_PATH_PARSER.parse(this)

internal data class ViewTarget(val tenantId: String, val viewId: String)

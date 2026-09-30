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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import org.junit.jupiter.api.Test

class ViewStorePathsTest {
    private val hosted = ViewStorePaths(MaterializedNamedBoundedContext("compensation-service"))

    @Test
    fun `a host of another context serves the view store under its alias`() {
        hosted.prefix.assert().isEqualTo("/view-store")
        hosted.systemViews.assert().isEqualTo("/view-store/tenant/{tenantId}/owner/{ownerId}/system-views")
        hosted.preferences.assert()
            .isEqualTo("/view-store/tenant/{tenantId}/owner/{ownerId}/definitions/{definitionId}/preferences")
        hosted.replay.assert().isEqualTo("/view-store/tenant/{tenantId}/owner/{ownerId}/view/requests/{requestId}")
    }

    @Test
    fun `the view store's own context serves it without a prefix, as Wow does`() {
        val own = ViewStorePaths(MaterializedNamedBoundedContext("view-store"))
        own.prefix.assert().isEmpty()
        own.scope.assert().isEqualTo("/tenant/{tenantId}/owner/{ownerId}")
    }

    @Test
    fun `recognizes the view store's paths and the view they address`() {
        hosted.isViewStorePath("/view-store/tenant/t1/owner/alice/view").assert().isTrue()
        hosted.isViewStorePath("/view-store/tenant/t1/owner/(shared)").assert().isTrue()
        hosted.isViewStorePath("/view-store/owner/alice/view/snapshot/list").assert().isFalse()
        hosted.isViewStorePath("/execution_failed/1/state").assert().isFalse()
        hosted.viewTarget("/view-store/tenant/t1/owner/alice/view/v1/rename").assert()
            .isEqualTo(ViewTarget("t1", "v1"))
        hosted.viewTarget("/view-store/tenant/t1/owner/alice/view/v1").assert().isEqualTo(ViewTarget("t1", "v1"))
        hosted.viewTarget("/view-store/tenant/t1/owner/alice/view").assert().isNull()
    }

    @Test
    fun `sees a path as Spring routes it, decoded and without parameters`() {
        listOf(
            "/view-store;x=1/tenant/t1/owner/alice/view/v1/rename",
            "/view%2Dstore/tenant/t1/owner/alice/view/v1/rename",
            "/view-store/tenant/t1/%6Fwner/alice/view/v1/rename",
            "/view-store/tenant;a=b/t1/owner/alice/view;v=2/v1/rename",
        ).forEach { path ->
            hosted.isViewStorePath(path).assert().isTrue()
            hosted.viewTarget(path).assert().isEqualTo(ViewTarget("t1", "v1"))
        }
        hosted.viewTarget("/view-store/tenant/t%31/owner/alice/view/v%31;x/rename").assert()
            .isEqualTo(ViewTarget("t1", "v1"))
    }

    @Test
    fun `a tenant or owner is a name, never blank or with whitespace or control characters`() {
        hosted.hasValidScope("/view-store/tenant/t1/owner/alice/view/v1/rename").assert().isTrue()
        hosted.hasValidScope("/view-store/tenant/t1/owner/(shared)/view").assert().isTrue()
        hosted.hasValidScope("/view-store/tenant/(0)/owner/alice%2Dx/view").assert().isTrue()
        listOf(
            "%20", "%09", "%E3%80%80", "%C2%A0", "%00", "%0A", "%20%20", "%20;x=alice", "alice%20", "%20alice", "al%20ice",
            "alice%E2%80%8B", "%EF%BB%BFalice", "al%C2%ADice", "alice%E2%81%A0", "alice%E1%A0%8E", "%E2%80%A8",
        )
            .forEach { blank ->
                hosted.hasValidScope("/view-store/tenant/t1/owner/$blank/view/v1/rename").assert().isFalse()
                hosted.hasValidScope("/view-store/tenant/$blank/owner/alice/view").assert().isFalse()
            }
        hosted.hasValidScope("/cart/c1").assert().isFalse()
    }

    @Test
    fun `matches the view store's paths in any case, as a case-insensitive host routes them`() {
        listOf(
            "/VIEW-STORE/tenant/t1/owner/alice/view/v1/rename",
            "/view-store/TENANT/t1/Owner/alice/VIEW/v1/rename",
        ).forEach { path ->
            hosted.isViewStorePath(path).assert().isTrue()
            hosted.viewTarget(path).assert().isEqualTo(ViewTarget("t1", "v1"))
        }
        hosted.hasValidScope("/view-store/tenant/t1/OWNER/%20/view").assert().isFalse()
        // The variables keep their case.
        hosted.viewTarget("/view-store/tenant/T1/owner/alice/view/V1").assert().isEqualTo(ViewTarget("T1", "V1"))
    }
}

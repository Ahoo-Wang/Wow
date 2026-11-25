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

package me.ahoo.wow.cosec

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.identity.IdentityFormatException
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * The `CoSec-Identity` header a local client or a test sends a service behind the CoSec gateway (#6466), written as
 * CoSec documents it, format version 1: base64url without padding of its JSON. The services' tests behind CoSec's
 * gateway mode read it through CoSec's own pipeline.
 */
@OptIn(ExperimentalWowApi::class, InternalCoSecApi::class) // CoSec's decoder is the one its services read with.
class CoSecIdentityTest {
    @Test
    fun `the header should be the documented JSON, base64url without padding`() {
        val before = Instant.now().epochSecond
        val header = CoSecIdentity(
            "alice",
            listOf("buyer", "buyer", "auditor"),
            tenant = "acme",
            app = "shop",
            space = "east",
            ttl = Duration.ofMinutes(5),
        ).header()
        header.assert().doesNotContain("=").doesNotContain("+").doesNotContain("/")
        val identity = WowJson.mapper.readTree(Base64.getUrlDecoder().decode(header))
        identity.path("v").intValue().assert().isEqualTo(1)
        identity.path("tenant").stringValue().assert().isEqualTo("acme")
        identity.path("sub").stringValue().assert().isEqualTo("alice")
        identity.path("scope").path("app").stringValue().assert().isEqualTo("shop")
        identity.path("scope").path("space").stringValue().assert().isEqualTo("east")
        val roles = identity.path("roles")
        List(roles.size()) { roles[it].stringValue() }.assert().containsExactly("buyer", "auditor")
        identity.path("auth").path("method").stringValue().assert().isEqualTo("jwt")
        identity.path("auth").path("kind").stringValue().assert().isEqualTo("person")
        identity.path("auth").path("jti").stringValue().assert().isNotBlank()
        identity.path("rid").stringValue().assert().isNotBlank()
        val issued = identity.path("iat").longValue()
        (issued >= before).assert().isTrue()
        identity.path("auth").path("exp").longValue().assert().isEqualTo(issued + 300)
    }

    @Test
    fun `the defaults should be CoSec's default tenant and scope, and the headers one of its name`() {
        val headers = CoSecIdentity("bob").headers()
        headers.keys.assert().containsExactly(CoSecIdentity.HEADER)
        val identity = WowJson.mapper.readTree(Base64.getUrlDecoder().decode(headers.getValue("CoSec-Identity")))
        identity.path("tenant").stringValue().assert().isEqualTo(CoSecIdentity.DEFAULT_TENANT)
        identity.path("scope").path("app").stringValue().assert().isEmpty()
        identity.path("roles").isEmpty.assert().isTrue()
    }

    @Test
    fun `CoSec's own decoder should read the header as written, strict as a service behind the gateway is`() {
        val header = CoSecIdentity(
            "alice",
            listOf("buyer", "auditor"),
            tenant = "acme",
            app = "shop",
            space = "east",
            ttl = Duration.ofMinutes(5),
        ).header()
        val identity = IdentityCodec.decode(header)
        identity.tenant.assert().isEqualTo("acme")
        identity.subject.assert().isEqualTo("alice")
        identity.app.assert().isEqualTo("shop")
        identity.space.assert().isEqualTo("east")
        identity.roles.assert().containsExactly("buyer", "auditor")
        identity.credential.method.assert().isEqualTo(Identity.Method.JWT)
        // IDN-44: a person's, which CoSec reads only from auth.kind; without it the credential is a workload's.
        identity.credential.kind.assert().isEqualTo(Identity.Credential.Kind.PERSON)
        identity.credential.expiresAt.assert().isEqualTo(identity.issuedAt.plus(Duration.ofMinutes(5)))
        identity.requestId.assert().isNotBlank()
        // No memberships (IDN-39): a service in gateway mode reads none as no other memberships; only a host that
        // decides on the forwarded identity (cosec.pipeline.caller=forwarded-identity) requires them.
        identity.memberships.assert().isNull()
        IdentityCodec.decode(CoSecIdentity("bob").header()).tenant.assert().isEqualTo(CoSecIdentity.DEFAULT_TENANT)
    }

    @Test
    fun `CoSec's decoder should refuse the header of a blank subject, which no service behind the gateway reads`() {
        // IDN-38: a forwarded identity whose sub is blank is malformed, answered 400 before the service's routes.
        assertThrows<IdentityFormatException> { IdentityCodec.decode(CoSecIdentity(" ").header()) }
    }
}

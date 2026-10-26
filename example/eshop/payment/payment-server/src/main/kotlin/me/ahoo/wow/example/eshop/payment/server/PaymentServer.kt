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

package me.ahoo.wow.example.eshop.payment.server

import me.ahoo.wow.example.eshop.payment.domain.payment.PaymentGateway
import me.ahoo.wow.example.eshop.payment.domain.payment.SimulatedPaymentGateway
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.spring.boot.DevelopmentMode
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.io.File
import java.math.BigDecimal

/**
 * The eShop's payment service on the Wow host: the host finds the payment model and the sagas through
 * `eshop-payment-domain`'s binding index and serves its routes on the public port and the cluster on the internal one.
 * It runs on the memory backend by default and on PostgreSQL with the `postgres` profile ([PostgresDataSource]).
 *
 * Its sagas read the ordering's order stream and send the ordering's commands, so the node needs Kafka: the `kafka`
 * profile gives it, and it does not start without a command transport.
 */
@SpringBootApplication(proxyBeanMethods = false)
class PaymentServer {
    /**
     * The simulated gateway, which approves up to `eshop.payment.gateway.limit` (1000.00 by default), and is down while
     * the file `eshop.payment.gateway.outage-file` exists (none by default).
     */
    @Bean
    fun paymentGateway(
        @Value("\${eshop.payment.gateway.limit:1000.00}") limit: BigDecimal,
        @Value("\${eshop.payment.gateway.outage-file:}") outageFile: String,
    ): PaymentGateway {
        val outage = outageFile.takeIf { it.isNotBlank() }?.let(::File)
        return SimulatedPaymentGateway(limit) { outage?.exists() == true }
    }

    /**
     * Who calls the service in development: no one in particular, the anonymous caller of the default tenant, so the
     * service runs without an identity provider on a laptop and in its tests. Its commands come from the ordering
     * context's sagas over the cluster. Behind the CoSec gateway (the `cosec` profile, decisions D295, D296), this
     * resolver steps aside for wow-cosec's, which takes the caller from the principal the gateway verified (decision
     * D301); outside development, a node without CoSec does not start.
     */
    @Bean
    @ConditionalOnProperty(COSEC_ENABLED, havingValue = "false", matchIfMissing = true)
    fun anonymousCallers(development: DevelopmentMode): CallerResolver {
        development.require(COSEC_ENABLED, false, "serves every request as the anonymous caller")
        return CallerResolver.ANONYMOUS
    }

    companion object {
        /** CoSec's switch: off in development, on behind the gateway. */
        const val COSEC_ENABLED: String = "cosec.enabled"
    }
}

fun main(args: Array<String>) {
    runApplication<PaymentServer>(*args)
}

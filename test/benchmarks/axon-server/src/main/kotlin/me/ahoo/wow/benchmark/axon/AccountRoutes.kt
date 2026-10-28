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

package me.ahoo.wow.benchmark.axon

import org.axonframework.commandhandling.CommandExecutionException
import org.axonframework.commandhandling.gateway.CommandGateway
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyToMono
import org.springframework.web.reactive.function.server.router
import reactor.core.publisher.Mono
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The routes of the Wow benchmark server that the load generator's E1 and E3 use (test/benchmarks/load,
 * `TransferApi.kt`), answered as Wow answers them: `{"status":"succeeded","id":…,"version":…}` once the command
 * reached its completion point. E1's `deposit` completes when its events are stored (the command's unit of work
 * committed); E3's `prepare` when the transfer's entry is stored on the target. `open-account` seeds. The `Wow-Wait`
 * headers are not read: each route has its scenario's completion point. `GET /wow/manifest` is the harness's
 * readiness probe.
 *
 * The command bus handles a command on the thread that sends it and blocks on JDBC, so each command is sent from a
 * virtual thread of its own, never from Netty's event loop.
 */
@Configuration
class AccountRoutes(private val commands: CommandGateway, private val waits: TransferWaits) {
    private val senders = Executors.newVirtualThreadPerTaskExecutor()

    @Bean
    fun accountRouter(): RouterFunction<ServerResponse> = router {
        GET("/wow/manifest") { ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue("{}") }
        POST("/transfer/account/{id}/open-account") { request ->
            request.bodyToMono<OpenBody>().flatMap { body ->
                command(request.id) { OpenAccount(request.id, body.owner, body.balance) }
            }
        }
        POST("/transfer/account/{id}/deposit") { request ->
            request.bodyToMono<DepositBody>().flatMap { body -> command(request.id) { Deposit(request.id, body.amount) } }
        }
        POST("/transfer/account/{id}/prepare") { request ->
            request.bodyToMono<PrepareBody>().flatMap { body -> transfer(request.id, body) }
        }
    }

    private val ServerRequest.id: String get() = pathVariable("id")

    /** Sends the command [of] gives and answers once it is handled, with the aggregate's version. */
    private fun command(id: String, of: () -> Any): Mono<ServerResponse> =
        answer(CompletableFuture.supplyAsync({ commands.sendAndWait<Long?>(of()) }, senders).thenApply { id to it })

    /** Sends the transfer's `Prepare` and answers once the target's entry is stored. */
    private fun transfer(source: String, body: PrepareBody): Mono<ServerResponse> {
        val transferId = UUID.randomUUID().toString()
        val entered = waits.await(transferId)
        val prepared = CompletableFuture.supplyAsync(
            { commands.sendAndWait<Long?>(Prepare(source, body.to, body.amount, transferId)) },
            senders,
        )
        val chain = prepared.thenCompose { entered }
            .orTimeout(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .whenComplete { _, _ -> waits.forget(transferId) }
            .thenApply { it.target to it.version }
        return answer(chain)
    }

    private fun answer(result: CompletableFuture<Pair<String, Long?>>): Mono<ServerResponse> =
        Mono.fromFuture(result).flatMap { (id, version) ->
            ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"status":"succeeded","id":"$id","version":${version ?: "null"}}""")
        }.onErrorResume { failure -> failed(failure) }

    private fun failed(failure: Throwable): Mono<ServerResponse> {
        val cause = (failure as? CompletionException)?.cause ?: failure
        val rejected = generateSequence(cause) { it.cause }.filterIsInstance<AccountRejected>().firstOrNull()
            ?: (cause as? CommandExecutionException)?.getDetails<String>()?.orElse(null)?.let { AccountRejected(it, "") }
        val (status, code) = when {
            rejected != null -> HttpStatus.BAD_REQUEST to rejected.code
            cause is TimeoutException -> HttpStatus.GATEWAY_TIMEOUT to "timeout"
            else -> HttpStatus.INTERNAL_SERVER_ERROR to cause.javaClass.simpleName
        }
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"status":"failed","code":"$code"}""")
    }

    data class OpenBody(val owner: String, val balance: Long)

    data class DepositBody(val amount: Long)

    data class PrepareBody(val to: String, val amount: Long)

    private companion object {
        /** As the Wow benchmark's wait timeout (`Wow-Wait-Timeout`, 10 s). */
        const val WAIT_TIMEOUT_SECONDS = 10L
    }
}

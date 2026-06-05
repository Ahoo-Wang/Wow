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

package me.ahoo.wow.command.factory

import jakarta.validation.ConstraintViolation
import jakarta.validation.Validator
import jakarta.validation.executable.ExecutableValidator
import jakarta.validation.metadata.BeanDescriptor
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.AggregateName
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.traceId
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class SimpleCommandMessageFactoryBehaviorTest {

    @Test
    fun `should create message directly when no rewriter is registered`() {
        val validator = RecordingValidator()
        val registry = MapCommandBuilderRewriterRegistry()
        val factory = SimpleCommandMessageFactory(validator, registry)
        val header = DefaultHeader.empty().with("source", "direct")
        val body = FactoryMessageCommand("payload")
        val builder = body.commandBuilder()
            .id("command-1")
            .requestId("request-1")
            .aggregateId("aggregate-1")
            .tenantId("tenant-1")
            .ownerId("owner-1")
            .spaceId("space-1")
            .aggregateVersion(4)
            .namedAggregate(MaterializedNamedAggregate("factory", "account"))
            .header(header)
            .createTime(2222)

        StepVerifier.create(factory.create<FactoryMessageCommand>(builder))
            .assertNext { message ->
                message.id.assert().isEqualTo("command-1")
                message.requestId.assert().isEqualTo("request-1")
                message.aggregateId.id.assert().isEqualTo("aggregate-1")
                message.aggregateId.tenantId.assert().isEqualTo("tenant-1")
                message.contextName.assert().isEqualTo("factory")
                message.aggregateName.assert().isEqualTo("account")
                message.ownerId.assert().isEqualTo("owner-1")
                message.spaceId.assert().isEqualTo("space-1")
                message.aggregateVersion.assert().isEqualTo(4)
                message.header.assert().isSameAs(header)
                message.header.traceId.assert().isEqualTo("command-1")
                message.createTime.assert().isEqualTo(2222)
                message.body.assert().isSameAs(body)
            }.verifyComplete()

        validator.validatedBodies.assert().isEmpty()
    }

    @Test
    fun `should validate and rewrite builder when rewriter is registered`() {
        val validator = RecordingValidator()
        val rewriter = object : CommandBuilderRewriter {
            override val supportedCommandType: Class<*> = FactoryMessageCommand::class.java

            override fun rewrite(commandBuilder: CommandBuilder): Mono<CommandBuilder> =
                Mono.just(
                    commandBuilder
                        .requestId("rewritten-request")
                        .aggregateId("rewritten-aggregate")
                        .ownerId("rewritten-owner")
                        .createTime(3333)
                )
        }
        val registry = MapCommandBuilderRewriterRegistry(rewriter)
        val factory = SimpleCommandMessageFactory(validator, registry)
        val body = FactoryMessageCommand("payload")
        val builder = body.commandBuilder()
            .id("command-1")
            .tenantId("tenant-1")
            .namedAggregate(MaterializedNamedAggregate("factory", "account"))

        StepVerifier.create(factory.create<FactoryMessageCommand>(builder))
            .assertNext { message ->
                message.id.assert().isEqualTo("command-1")
                message.requestId.assert().isEqualTo("rewritten-request")
                message.aggregateId.id.assert().isEqualTo("rewritten-aggregate")
                message.aggregateId.tenantId.assert().isEqualTo("tenant-1")
                message.ownerId.assert().isEqualTo("rewritten-owner")
                message.createTime.assert().isEqualTo(3333)
            }.verifyComplete()

        validator.validatedBodies.assert().isEqualTo(listOf(body))
    }

    @Test
    fun `should fail when registered rewriter returns no command`() {
        val rewriter = object : CommandBuilderRewriter {
            override val supportedCommandType: Class<*> = FactoryMessageCommand::class.java

            override fun rewrite(commandBuilder: CommandBuilder): Mono<CommandBuilder> = Mono.empty()
        }
        val registry = MapCommandBuilderRewriterRegistry(rewriter)
        val factory = SimpleCommandMessageFactory(RecordingValidator(), registry)
        val builder = FactoryMessageCommand("payload")
            .commandBuilder()
            .id("command-1")
            .namedAggregate(MaterializedNamedAggregate("factory", "account"))

        StepVerifier.create(factory.create<FactoryMessageCommand>(builder))
            .expectErrorSatisfies {
                it.assert().isInstanceOf(RewriteNoCommandException::class.java)
                val exception = it as RewriteNoCommandException
                exception.commandBuilder.assert().isSameAs(builder)
                exception.rewriter.assert().isSameAs(rewriter)
            }.verify()
    }

    @Test
    fun `should create from command body through default builder`() {
        val factory = SimpleCommandMessageFactory(RecordingValidator(), MapCommandBuilderRewriterRegistry())
        val body = FactoryAnnotatedMessageCommand(aggregate = "factory.account")

        StepVerifier.create(factory.create<FactoryAnnotatedMessageCommand>(body))
            .assertNext { message ->
                message.body.assert().isSameAs(body)
                message.contextName.assert().isEqualTo("factory")
                message.aggregateName.assert().isEqualTo("account")
                message.requestId.assert().isEqualTo(message.id)
            }.verifyComplete()
    }
}

private data class FactoryMessageCommand(val value: String)

private data class FactoryAnnotatedMessageCommand(
    @AggregateName val aggregate: String
)

private class MapCommandBuilderRewriterRegistry(
    vararg rewriters: CommandBuilderRewriter
) : CommandBuilderRewriterRegistry {
    private val rewriters = rewriters.associateBy { it.supportedCommandType }.toMutableMap()

    override fun register(rewriter: CommandBuilderRewriter) {
        rewriters[rewriter.supportedCommandType] = rewriter
    }

    override fun unregister(commandType: Class<*>) {
        rewriters.remove(commandType)
    }

    override fun getRewriter(commandType: Class<*>): CommandBuilderRewriter? = rewriters[commandType]
}

private class RecordingValidator : Validator {
    val validatedBodies = mutableListOf<Any>()

    override fun <T : Any> validate(
        `object`: T,
        vararg groups: Class<*>
    ): Set<ConstraintViolation<T>> {
        validatedBodies += `object`
        return emptySet()
    }

    override fun <T : Any> validateProperty(
        `object`: T,
        propertyName: String,
        vararg groups: Class<*>
    ): Set<ConstraintViolation<T>> = emptySet()

    override fun <T : Any> validateValue(
        beanType: Class<T>,
        propertyName: String,
        value: Any,
        vararg groups: Class<*>
    ): Set<ConstraintViolation<T>> = emptySet()

    override fun getConstraintsForClass(clazz: Class<*>): BeanDescriptor = throw UnsupportedOperationException()

    override fun <T : Any> unwrap(type: Class<T>): T = throw UnsupportedOperationException()

    override fun forExecutables(): ExecutableValidator = throw UnsupportedOperationException()
}

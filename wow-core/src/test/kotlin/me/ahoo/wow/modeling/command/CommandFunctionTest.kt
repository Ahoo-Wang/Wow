package me.ahoo.wow.modeling.command

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.messaging.function.MessageFunction
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class CommandFunctionTest {

    @Test
    fun getAnnotation() {
        val delegate = mockk<MessageFunction<Any, ServerCommandExchange<*>, Mono<*>>> {
            every { getAnnotation(Retry::class.java) } returns null
            every { supportedType } returns Any::class.java
            every { functionKind } returns FunctionKind.COMMAND
            every { contextName } returns "context"
            every { processor } returns "root"
            every { name } returns "name"
        }
        val commandAggregate = mockk<CommandAggregate<Any, Any>> {
            every { contextName } returns "context"
            every { aggregateName } returns "aggregate"
        }
        CommandFunction(
            delegate = delegate,
            commandAggregate = commandAggregate,
            afterCommandFunctions = emptyList()
        ).getAnnotation(Retry::class.java).assert().isNull()
    }
}

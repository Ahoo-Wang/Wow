package me.ahoo.wow.exception

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.CommandResultException
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.id.generateGlobalId
import org.junit.jupiter.api.Test

class ErrorInfoConverterRegistrarTest {

    @Test
    fun `should register`() {
        CustomException().toErrorInfo().errorCode.assert().isEqualTo("CUSTOM_EXCEPTION")
        ErrorInfoConverterRegistrar.unregister(CustomException::class.java).assert()
            .isEqualTo(CustomExceptionErrorInfoConverter)
        CustomException().toErrorInfo().errorCode.assert().isEqualTo(ErrorCodes.BAD_REQUEST)
        ErrorInfoConverterRegistrar.register(CustomExceptionErrorInfoConverterFactory()).assert().isNull()
        CustomException().toErrorInfo().errorCode.assert().isEqualTo("CUSTOM_EXCEPTION")
    }

    @Test
    fun `should command result exception to error info`() {
        val commandResult = CommandResult(
            id = generateGlobalId(),
            waitCommandId = generateGlobalId(),
            stage = CommandStage.SENT,
            aggregateId = generateGlobalId(),
            tenantId = generateGlobalId(),
            requestId = generateGlobalId(),
            commandId = generateGlobalId(),
            contextName = "contextName",
            aggregateName = "aggregateName",
            function = FunctionInfoData(
                functionKind = FunctionKind.COMMAND,
                contextName = "contextName",
                processorName = "processorName",
                name = "name"
            ),
            errorCode = ErrorCodes.NOT_FOUND
        )
        CommandResultException(commandResult).toErrorInfo().assert().isEqualTo(commandResult)
    }
}

class CustomException : RuntimeException()

object CustomExceptionErrorInfoConverter : ErrorInfoConverter<CustomException> {
    override fun convert(error: CustomException): ErrorInfo {
        return ErrorInfo.of("CUSTOM_EXCEPTION")
    }
}

class CustomExceptionErrorInfoConverterFactory : AbstractErrorInfoConverterFactory<CustomException>() {

    override fun create(): ErrorInfoConverter<CustomException> {
        return CustomExceptionErrorInfoConverter
    }
}

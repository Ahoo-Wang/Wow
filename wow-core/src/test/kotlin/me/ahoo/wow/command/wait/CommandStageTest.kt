package me.ahoo.wow.command.wait

import me.ahoo.test.asserts.assert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class CommandStageTest {

    @ParameterizedTest
    @MethodSource("shouldNotifyArgsProvider")
    fun shouldNotify(commandStage: CommandStage, processingStage: CommandStage, expected: Boolean) {
        commandStage.shouldNotify(processingStage).assert().isEqualTo(expected)
    }

    @ParameterizedTest
    @MethodSource("isAfterArgsProvider")
    fun isAfter(commandStage: CommandStage, processingStage: CommandStage, expected: Boolean) {
        commandStage.isPrevious(processingStage).assert().isEqualTo(expected)
    }

    companion object {
        @JvmStatic
        fun shouldNotifyArgsProvider(): Stream<Arguments> {
            return Stream.of(
                Arguments.arguments(CommandStage.SENT, CommandStage.SENT, true),
                Arguments.arguments(CommandStage.SENT, CommandStage.PROCESSED, false),
                Arguments.arguments(CommandStage.SENT, CommandStage.SNAPSHOT, false),
                Arguments.arguments(CommandStage.SENT, CommandStage.PROJECTED, false),
                Arguments.arguments(CommandStage.SENT, CommandStage.EVENT_HANDLED, false),
                Arguments.arguments(CommandStage.PROCESSED, CommandStage.SENT, true),
                Arguments.arguments(CommandStage.PROCESSED, CommandStage.PROCESSED, true),
                Arguments.arguments(CommandStage.PROCESSED, CommandStage.SNAPSHOT, false),
                Arguments.arguments(CommandStage.PROCESSED, CommandStage.PROJECTED, false),
                Arguments.arguments(CommandStage.PROCESSED, CommandStage.EVENT_HANDLED, false),
                Arguments.arguments(CommandStage.SNAPSHOT, CommandStage.SENT, true),
                Arguments.arguments(CommandStage.SNAPSHOT, CommandStage.PROCESSED, true),
                Arguments.arguments(CommandStage.SNAPSHOT, CommandStage.SNAPSHOT, true),
                Arguments.arguments(CommandStage.SNAPSHOT, CommandStage.PROJECTED, false),
                Arguments.arguments(CommandStage.SNAPSHOT, CommandStage.EVENT_HANDLED, false),
                Arguments.arguments(CommandStage.PROJECTED, CommandStage.SENT, true),
                Arguments.arguments(CommandStage.PROJECTED, CommandStage.PROCESSED, true),
                Arguments.arguments(CommandStage.PROJECTED, CommandStage.SNAPSHOT, false),
                Arguments.arguments(CommandStage.PROJECTED, CommandStage.PROJECTED, true),
                Arguments.arguments(CommandStage.PROJECTED, CommandStage.EVENT_HANDLED, false),
                Arguments.arguments(CommandStage.EVENT_HANDLED, CommandStage.SENT, true),
                Arguments.arguments(CommandStage.EVENT_HANDLED, CommandStage.PROCESSED, true),
                Arguments.arguments(CommandStage.EVENT_HANDLED, CommandStage.EVENT_HANDLED, true),
                Arguments.arguments(CommandStage.SAGA_HANDLED, CommandStage.EVENT_HANDLED, false),
                Arguments.arguments(CommandStage.SAGA_HANDLED, CommandStage.SENT, true),
                Arguments.arguments(CommandStage.SAGA_HANDLED, CommandStage.PROCESSED, true),
                Arguments.arguments(CommandStage.SAGA_HANDLED, CommandStage.SAGA_HANDLED, true),
                Arguments.arguments(CommandStage.SAGA_HANDLED, CommandStage.EVENT_HANDLED, false),
            )
        }

        @JvmStatic
        fun isAfterArgsProvider(): Stream<Arguments> {
            return Stream.of(
                Arguments.arguments(CommandStage.SENT, CommandStage.SENT, false),
                Arguments.arguments(CommandStage.SENT, CommandStage.PROCESSED, false),
                Arguments.arguments(CommandStage.SENT, CommandStage.SNAPSHOT, false),
                Arguments.arguments(CommandStage.SENT, CommandStage.PROJECTED, false),
                Arguments.arguments(CommandStage.PROCESSED, CommandStage.SENT, true),
                Arguments.arguments(CommandStage.PROCESSED, CommandStage.PROCESSED, false),
                Arguments.arguments(CommandStage.PROCESSED, CommandStage.SNAPSHOT, false),
                Arguments.arguments(CommandStage.PROCESSED, CommandStage.PROJECTED, false),
                Arguments.arguments(CommandStage.SNAPSHOT, CommandStage.SENT, true),
                Arguments.arguments(CommandStage.SNAPSHOT, CommandStage.PROCESSED, true),
                Arguments.arguments(CommandStage.SNAPSHOT, CommandStage.SNAPSHOT, false),
                Arguments.arguments(CommandStage.SNAPSHOT, CommandStage.PROJECTED, false),
                Arguments.arguments(CommandStage.PROJECTED, CommandStage.SENT, true),
                Arguments.arguments(CommandStage.PROJECTED, CommandStage.PROCESSED, true),
                Arguments.arguments(CommandStage.PROJECTED, CommandStage.SNAPSHOT, false),
                Arguments.arguments(CommandStage.PROJECTED, CommandStage.PROJECTED, false),
                Arguments.arguments(CommandStage.EVENT_HANDLED, CommandStage.SENT, true),
                Arguments.arguments(CommandStage.EVENT_HANDLED, CommandStage.PROCESSED, true),
                Arguments.arguments(CommandStage.EVENT_HANDLED, CommandStage.SNAPSHOT, false),
                Arguments.arguments(CommandStage.SAGA_HANDLED, CommandStage.SENT, true),
                Arguments.arguments(CommandStage.SAGA_HANDLED, CommandStage.PROCESSED, true),
                Arguments.arguments(CommandStage.SAGA_HANDLED, CommandStage.SNAPSHOT, false),
            )
        }
    }
}

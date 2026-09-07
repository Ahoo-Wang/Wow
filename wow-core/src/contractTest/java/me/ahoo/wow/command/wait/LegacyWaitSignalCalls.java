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

package me.ahoo.wow.command.wait;

/** Compiles the Java entry points available before request metadata was added. */
public final class LegacyWaitSignalCalls {
    private LegacyWaitSignalCalls() {
    }

    public static WaitSignal[] createFrom(SimpleWaitSignal signal) {
        return new WaitSignal[] {
            new SimpleWaitSignal(
                signal.getId(), signal.getWaitCommandId(), signal.getCommandId(), signal.getAggregateId(),
                signal.getStage(), signal.getFunction(), signal.getAggregateVersion(), signal.isLastProjection(),
                signal.getErrorCode(), signal.getErrorMsg(), signal.getBindingErrors(), signal.getResult(),
                signal.getCommands(), signal.getSignalTime()
            ),
            SimpleWaitSignal.Companion.toWaitSignal(
                signal.getFunction(), signal.getId(), signal.getWaitCommandId(), signal.getCommandId(),
                signal.getAggregateId(), signal.getStage(), signal.isLastProjection(), signal.getAggregateVersion(),
                signal.getErrorCode(), signal.getErrorMsg(), signal.getBindingErrors(), signal.getResult(),
                signal.getCommands(), signal.getSignalTime()
            ),
            signal.copy(
                signal.getId(), signal.getWaitCommandId(), signal.getCommandId(), signal.getAggregateId(),
                signal.getStage(), signal.getFunction(), signal.getAggregateVersion(), signal.isLastProjection(),
                signal.getErrorCode(), signal.getErrorMsg(), signal.getBindingErrors(), signal.getResult(),
                signal.getCommands(), signal.getSignalTime()
            )
        };
    }
}

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

import type { AggregateId, ErrorInfo, FunctionInfoCapable, Identifier } from '../types';

export interface CommandId {
  commandId: string;
}

export interface WaitCommandIdCapable {
  waitCommandId: string;
}

export interface RequestId {
  requestId: string;
}

export enum CommandStage {
  /**
   * 当命令发布到命令总线/队列后，生成完成信号.
   */
  SENT = 'SENT',
  /**
   * 当命令被聚合根处理完成后，生成完成信号.
   */
  PROCESSED = 'PROCESSED',
  /**
   * 当快照被生成后，生成完成信号.
   */
  SNAPSHOT = 'SNAPSHOT',
  /**
   * 当命令产生的事件*投影*完成后，生成完成信号.
   */
  PROJECTED = 'PROJECTED',
  /**
   * 当命令产生的事件被*事件处理器*处理完成后，生成完成信号.
   */
  EVENT_HANDLED = 'EVENT_HANDLED',

  /**
   * 当命令产生的事件被*Saga*处理完成后，生成完成信号.
   */
  SAGA_HANDLED = 'SAGA_HANDLED'
}

export interface CommandStageCapable {
  stage: CommandStage;
}

export interface CommandResultCapable {
  result: Record<string, any>;
}

export interface SignalTimeCapable {
  signalTime: number;
}

export interface CommandResult
  extends Identifier, WaitCommandIdCapable, CommandStageCapable, NamedBoundedContext,
    AggregateNameCapable,
    AggregateId,
    ErrorInfo,
    CommandId,
    RequestId, ErrorInfo, FunctionInfoCapable, CommandResultCapable, SignalTimeCapable {
  aggregateVersion?: number;

}
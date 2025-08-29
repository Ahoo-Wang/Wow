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

import { describe, expect, it } from 'vitest';
import { CommandResult, CommandStage } from '../../src';
import { FunctionKind } from '../../src';

describe('CommandResult', () => {
  it('should create a command result with minimal properties', () => {
    const commandResult: CommandResult = {
      id: 'result-123',
      waitCommandId: 'wait-456',
      stage: CommandStage.PROCESSED,
      contextName: 'user-context',
      aggregateName: 'User',
      aggregateId: 'agg-789',
      errorCode: 'Ok',
      errorMsg: '',
      commandId: 'cmd-123',
      requestId: 'req-456',
      function: {
        functionKind: FunctionKind.COMMAND,
        contextName: 'user-context',
        processorName: 'UserProcessor',
        name: 'CreateUser',
      },
      signalTime: Date.now(),
      result: {},
      tenantId: 'tenant-1',
    };

    expect(commandResult).toBeDefined();
    expect(commandResult.id).toBe('result-123');
    expect(commandResult.waitCommandId).toBe('wait-456');
    expect(commandResult.stage).toBe(CommandStage.PROCESSED);
    expect(commandResult.contextName).toBe('user-context');
    expect(commandResult.aggregateName).toBe('User');
    expect(commandResult.aggregateId).toBe('agg-789');
    expect(commandResult.errorCode).toBe('Ok');
    expect(commandResult.errorMsg).toBe('');
    expect(commandResult.commandId).toBe('cmd-123');
    expect(commandResult.requestId).toBe('req-456');
    expect(commandResult.function.functionKind).toBe(FunctionKind.COMMAND);
    expect(commandResult.signalTime).toBeLessThanOrEqual(Date.now());
    expect(commandResult.result).toEqual({});
    expect(commandResult.tenantId).toBe('tenant-1');
  });

  it('should create a command result with all properties including optional ones', () => {
    const now = Date.now();
    const commandResult: CommandResult = {
      id: 'result-123',
      waitCommandId: 'wait-456',
      stage: CommandStage.SNAPSHOT,
      contextName: 'user-context',
      aggregateName: 'User',
      aggregateId: 'agg-789',
      errorCode: 'CustomError',
      errorMsg: 'Something went wrong',
      bindingErrors: [
        {
          name: 'field1',
          msg: 'Field1 is required',
        },
        {
          name: 'field2',
          msg: 'Field2 must be a number',
        },
      ],
      commandId: 'cmd-123',
      requestId: 'req-456',
      function: {
        functionKind: FunctionKind.COMMAND,
        contextName: 'user-context',
        processorName: 'UserProcessor',
        name: 'CreateUser',
      },
      signalTime: now,
      aggregateVersion: 2,
      result: {
        id: 'user-123',
        name: 'John Doe',
        email: 'john@example.com',
      },
      tenantId: 'tenant-1',
    };

    expect(commandResult).toBeDefined();
    expect(commandResult.id).toBe('result-123');
    expect(commandResult.waitCommandId).toBe('wait-456');
    expect(commandResult.stage).toBe(CommandStage.SNAPSHOT);
    expect(commandResult.contextName).toBe('user-context');
    expect(commandResult.aggregateName).toBe('User');
    expect(commandResult.aggregateId).toBe('agg-789');
    expect(commandResult.errorCode).toBe('CustomError');
    expect(commandResult.errorMsg).toBe('Something went wrong');
    expect(commandResult.bindingErrors).toHaveLength(2);
    expect(commandResult.bindingErrors?.[0].name).toBe('field1');
    expect(commandResult.bindingErrors?.[0].msg).toBe('Field1 is required');
    expect(commandResult.bindingErrors?.[1].name).toBe('field2');
    expect(commandResult.bindingErrors?.[1].msg).toBe(
      'Field2 must be a number',
    );
    expect(commandResult.commandId).toBe('cmd-123');
    expect(commandResult.requestId).toBe('req-456');
    expect(commandResult.function.functionKind).toBe(FunctionKind.COMMAND);
    expect(commandResult.function.contextName).toBe('user-context');
    expect(commandResult.function.processorName).toBe('UserProcessor');
    expect(commandResult.function.name).toBe('CreateUser');
    expect(commandResult.signalTime).toBe(now);
    expect(commandResult.aggregateVersion).toBe(2);
    expect(commandResult.result).toEqual({
      id: 'user-123',
      name: 'John Doe',
      email: 'john@example.com',
    });
    expect(commandResult.tenantId).toBe('tenant-1');
  });

  it('should create a command result with undefined aggregateVersion', () => {
    const commandResult: CommandResult = {
      id: 'result-123',
      waitCommandId: 'wait-456',
      stage: CommandStage.SENT,
      contextName: 'user-context',
      aggregateName: 'User',
      aggregateId: 'agg-789',
      errorCode: 'Ok',
      errorMsg: '',
      commandId: 'cmd-123',
      requestId: 'req-456',
      function: {
        functionKind: FunctionKind.COMMAND,
        contextName: 'user-context',
        processorName: 'UserProcessor',
        name: 'CreateUser',
      },
      signalTime: Date.now(),
      result: {},
      tenantId: 'tenant-1',
      // aggregateVersion is optional and can be undefined
    };

    expect(commandResult).toBeDefined();
    expect(commandResult.aggregateVersion).toBeUndefined();
  });
});

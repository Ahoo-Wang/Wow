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

import { describe, expect, it, vi } from 'vitest';
import {
  CommandResultEvent,
  CommandResultEventTransform,
  CommandResultEventTransformStream,
  toCommandResultEventStream,
} from '../../src/command/commandResultEventStream';
import { ServerSentEvent } from '@ahoo-wang/fetcher-eventstream';
import { CommandStage } from '../../src/command';
import { FunctionKind } from '../../src/types';

describe('CommandResultEventStream', () => {
  describe('CommandResultEventTransform', () => {
    it('should transform ServerSentEvent to CommandResultEvent', () => {
      const transform = new CommandResultEventTransform();
      const controller = {
        enqueue: vi.fn(),
      };

      const serverSentEvent: ServerSentEvent = {
        data: JSON.stringify({
          id: 'result-123',
          waitCommandId: 'wait-456',
          stage: CommandStage.PROCESSED,
          contextName: 'test-context',
          aggregateName: 'TestAggregate',
          aggregateId: 'agg-789',
          errorCode: 'Ok',
          errorMsg: '',
          commandId: 'cmd-123',
          requestId: 'req-456',
          function: {
            functionKind: FunctionKind.COMMAND,
            contextName: 'test-context',
            processorName: 'TestProcessor',
            name: 'TestCommand',
          },
          signalTime: Date.now(),
          result: {},
          tenantId: 'tenant-1',
        }),
        event: 'command-result',
        id: 'event-123',
        retry: 5000,
      };

      transform.transform(serverSentEvent, controller as any);

      expect(controller.enqueue).toHaveBeenCalledOnce();
      const calledWith = controller.enqueue.mock.calls[0][0];
      expect(calledWith.data.id).toBe('result-123');
      expect(calledWith.data.stage).toBe(CommandStage.PROCESSED);
      expect(calledWith.event).toBe('command-result');
      expect(calledWith.id).toBe('event-123');
      expect(calledWith.retry).toBe(5000);
    });

    it('should handle ServerSentEvent with minimal data', () => {
      const transform = new CommandResultEventTransform();
      const controller = {
        enqueue: vi.fn(),
      };

      const serverSentEvent: ServerSentEvent = {
        data: JSON.stringify({
          id: 'minimal-result',
          waitCommandId: 'wait-789',
          stage: CommandStage.SENT,
          contextName: 'minimal-context',
          aggregateName: 'MinimalAggregate',
          aggregateId: 'agg-456',
          errorCode: 'Ok',
          errorMsg: '',
          commandId: 'cmd-789',
          requestId: 'req-123',
          function: {
            functionKind: FunctionKind.COMMAND,
            contextName: 'minimal-context',
            processorName: 'MinimalProcessor',
            name: 'MinimalCommand',
          },
          signalTime: Date.now(),
          result: {},
          tenantId: 'tenant-1',
        }),
        event: 'minimal-event',
      };

      transform.transform(serverSentEvent, controller as any);

      expect(controller.enqueue).toHaveBeenCalledOnce();
      const calledWith = controller.enqueue.mock.calls[0][0];
      expect(calledWith.data.id).toBe('minimal-result');
      expect(calledWith.data.stage).toBe(CommandStage.SENT);
      expect(calledWith.event).toBe('minimal-event');
      expect(calledWith.id).toBeUndefined();
      expect(calledWith.retry).toBeUndefined();
    });
  });

  describe('CommandResultEventTransformStream', () => {
    it('should create a TransformStream instance', () => {
      const transformStream = new CommandResultEventTransformStream();
      expect(transformStream).toBeInstanceOf(TransformStream);
      expect(transformStream).toBeInstanceOf(CommandResultEventTransformStream);
    });
  });

  describe('toCommandResultEventStream', () => {
    it('should convert ServerSentEventStream to CommandResultEventStream', () => {
      // Create a mock ServerSentEventStream
      const mockServerSentEventStream = {
        pipeThrough: vi.fn().mockReturnValue('mocked-result-stream'),
      };

      const resultStream = toCommandResultEventStream(
        mockServerSentEventStream as any,
      );

      expect(mockServerSentEventStream.pipeThrough).toHaveBeenCalledOnce();
      expect(resultStream).toBe('mocked-result-stream');
    });
  });

  describe('Types', () => {
    it('should define CommandResultEvent type correctly', () => {
      const commandResultEvent: CommandResultEvent = {
        data: {
          id: 'type-test-result',
          waitCommandId: 'type-test-wait',
          stage: CommandStage.SNAPSHOT,
          contextName: 'type-test-context',
          aggregateName: 'TypeTestAggregate',
          aggregateId: 'type-test-agg',
          errorCode: 'Ok',
          errorMsg: '',
          commandId: 'type-test-cmd',
          requestId: 'type-test-req',
          function: {
            functionKind: FunctionKind.COMMAND,
            contextName: 'type-test-context',
            processorName: 'TypeTestProcessor',
            name: 'TypeTestCommand',
          },
          signalTime: Date.now(),
          result: {
            testData: 'value',
          },
          tenantId: 'tenant-1',
        } as any,
        event: 'type-test-event',
        id: 'type-test-id',
        retry: 3000,
      };

      expect(commandResultEvent.data.id).toBe('type-test-result');
      expect(commandResultEvent.data.stage).toBe(CommandStage.SNAPSHOT);
      expect(commandResultEvent.event).toBe('type-test-event');
      expect(commandResultEvent.id).toBe('type-test-id');
      expect(commandResultEvent.retry).toBe(3000);
      expect(commandResultEvent.data.result.testData).toBe('value');
    });
  });

  describe('CommandResultEventTransformStream', () => {
    it('should create a TransformStream instance', () => {
      const transformStream = new CommandResultEventTransformStream();
      expect(transformStream).toBeInstanceOf(TransformStream);
      expect(transformStream).toBeInstanceOf(CommandResultEventTransformStream);
    });
  });

  describe('toCommandResultEventStream', () => {
    it('should convert ServerSentEventStream to CommandResultEventStream', () => {
      // Create a mock ServerSentEventStream
      const mockServerSentEventStream = {
        pipeThrough: vi.fn().mockReturnValue('mocked-result-stream'),
      } as unknown as ReadableStream<ServerSentEvent>;

      const resultStream = toCommandResultEventStream(
        mockServerSentEventStream,
      );

      expect(mockServerSentEventStream.pipeThrough).toHaveBeenCalledOnce();
      expect(resultStream).toBe('mocked-result-stream');
    });
  });
});

/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)).
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

import { describe, expect, it, vi, beforeEach } from 'vitest';
import { parseOpenAPI } from '../../src/utils';
import { AggregateResolver } from '../../src/aggregate';
import { tagsToAggregates } from '../../src/aggregate/utils';

// Mock dependencies
vi.mock('../../src/aggregate/utils', () => ({
  tagsToAggregates: vi.fn(),
  operationIdToCommandName: vi.fn(),
}));

vi.mock('../../src/utils', () => ({
  parseOpenAPI: vi.fn(),
  extractOkResponse: vi.fn(),
  extractOperationOkResponseJsonSchema: vi.fn(),
  extractOperations: vi.fn(),
  extractParameter: vi.fn(),
  extractRequestBody: vi.fn(),
  extractSchema: vi.fn(),
  isReference: vi.fn(),
  keySchema: vi.fn(),
}));

import {
  extractOkResponse,
  extractOperationOkResponseJsonSchema,
  extractOperations,
  extractParameter,
  extractRequestBody,
  extractSchema,
  isReference,
  keySchema,
} from '../../src/utils';
import { operationIdToCommandName } from '../../src/aggregate/utils';

// Integration test
describe('AggregateResolver', () => {
  let mockOpenAPI: any;
  let mockTagsToAggregates: any;
  let mockExtractOperations: any;

  beforeEach(() => {
    vi.clearAllMocks();

    mockOpenAPI = {
      paths: {},
      tags: [],
      components: {
        parameters: {},
        responses: {},
        schemas: {},
      },
    };

    mockTagsToAggregates = vi.fn().mockReturnValue(new Map());
    (tagsToAggregates as any).mockImplementation(mockTagsToAggregates);

    mockExtractOperations = vi.fn().mockReturnValue([]);
    (extractOperations as any).mockImplementation(mockExtractOperations);
  });

  describe('constructor', () => {
    it('should initialize aggregates and call build', () => {
      const aggregateResolver = new AggregateResolver(mockOpenAPI);
      expect(mockTagsToAggregates).toHaveBeenCalledWith(mockOpenAPI.tags);
      expect(aggregateResolver['aggregates']).toBeDefined();
    });
  });

  describe('build', () => {
    it('should iterate through paths and process operations', () => {
      const mockPathItem = {};
      const mockMethodOperation = {
        method: 'post',
        operation: { operationId: 'test' },
      };

      mockOpenAPI.paths = { '/test': mockPathItem };
      mockExtractOperations.mockReturnValue([mockMethodOperation]);

      const aggregateResolver = new AggregateResolver(mockOpenAPI);

      expect(mockExtractOperations).toHaveBeenCalledWith(mockPathItem);
      // Verify that commands, state, events, fields are called
      // Since they are private methods, we can't directly spy, but we can check side effects
    });
  });

  describe('resolve', () => {
    it('should return empty map when no aggregates have state and fields', () => {
      const aggregateResolver = new AggregateResolver(mockOpenAPI);
      const result = aggregateResolver.resolve();
      expect(result).toBeInstanceOf(Map);
      expect(result.size).toBe(0);
    });

    it('should filter out aggregates without state or fields', () => {
      const mockAggregate = {
        aggregate: { contextAlias: 'test' },
        commands: new Map(),
        events: new Map(),
        // missing state and fields
      };

      mockTagsToAggregates.mockReturnValue(new Map([['tag1', mockAggregate]]));

      const aggregateResolver = new AggregateResolver(mockOpenAPI);
      const result = aggregateResolver.resolve();
      expect(result.size).toBe(0);
    });

    it('should include aggregates with state and fields', () => {
      const mockAggregate = {
        aggregate: {
          tag: { name: 'tag1' },
          aggregateName: 'TestAggregate',
          contextAlias: 'test',
        },
        commands: new Map(),
        events: new Map(),
        state: { key: 'state', schema: {} },
        fields: { key: 'fields', schema: {} },
      };

      mockTagsToAggregates.mockReturnValue(new Map([['tag1', mockAggregate]]));

      const aggregateResolver = new AggregateResolver(mockOpenAPI);
      const result = aggregateResolver.resolve();
      expect(result.size).toBe(1);
      expect(result.get('test')).toBeDefined();
      expect(result.get('test')!.has(mockAggregate)).toBe(true);
    });
  });

  describe('commands', () => {
    let aggregateResolver: AggregateResolver;
    let mockAggregate: any;

    beforeEach(() => {
      mockAggregate = {
        aggregate: {
          tag: { name: 'tag1' },
          aggregateName: 'TestAggregate',
          contextAlias: 'test',
        },
        commands: new Map(),
        events: new Map(),
      };

      mockTagsToAggregates.mockReturnValue(new Map([['tag1', mockAggregate]]));
      aggregateResolver = new AggregateResolver(mockOpenAPI);
    });

    it('should skip wow.command.send operations', () => {
      const methodOperation = {
        method: 'post',
        operation: { operationId: 'wow.command.send' },
      };

      (aggregateResolver as any).commands('/test', methodOperation);
      expect(mockAggregate.commands.size).toBe(0);
    });

    it('should skip operations without command name', () => {
      (operationIdToCommandName as any).mockReturnValue(null);

      const methodOperation = {
        method: 'post',
        operation: { operationId: 'test.operation' },
      };

      (aggregateResolver as any).commands('/test', methodOperation);
      expect(mockAggregate.commands.size).toBe(0);
    });

    it('should skip operations without ok response', () => {
      (operationIdToCommandName as any).mockReturnValue('TestCommand');
      (extractOkResponse as any).mockReturnValue(null);

      const methodOperation = {
        method: 'post',
        operation: { operationId: 'test.operation' },
      };

      (aggregateResolver as any).commands('/test', methodOperation);
      expect(mockAggregate.commands.size).toBe(0);
    });

    it('should skip operations with non-reference ok response', () => {
      (operationIdToCommandName as any).mockReturnValue('TestCommand');
      (extractOkResponse as any).mockReturnValue({ schema: {} });
      (isReference as any).mockReturnValue(false);

      const methodOperation = {
        method: 'post',
        operation: { operationId: 'test.operation' },
      };

      (aggregateResolver as any).commands('/test', methodOperation);
      expect(mockAggregate.commands.size).toBe(0);
    });

    it('should skip operations with wrong response ref', () => {
      (operationIdToCommandName as any).mockReturnValue('TestCommand');
      (extractOkResponse as any).mockReturnValue({
        $ref: '#/components/responses/wrong',
      });
      (isReference as any).mockReturnValue(true);

      const methodOperation = {
        method: 'post',
        operation: { operationId: 'test.operation' },
      };

      (aggregateResolver as any).commands('/test', methodOperation);
      expect(mockAggregate.commands.size).toBe(0);
    });

    it('should skip operations without request body', () => {
      (operationIdToCommandName as any).mockReturnValue('TestCommand');
      (extractOkResponse as any).mockReturnValue({
        $ref: '#/components/responses/wow.CommandOk',
      });
      (isReference as any).mockReturnValue(true);

      const methodOperation = {
        method: 'post',
        operation: { operationId: 'test.operation' },
      };

      (aggregateResolver as any).commands('/test', methodOperation);
      expect(mockAggregate.commands.size).toBe(0);
    });

    it('should add command to aggregate', () => {
      (operationIdToCommandName as any).mockReturnValue('TestCommand');
      (extractOkResponse as any).mockReturnValue({
        $ref: '#/components/responses/wow.CommandOk',
      });
      (isReference as any).mockReturnValue(true);
      (extractParameter as any).mockReturnValue({ name: 'id', in: 'path' });
      (keySchema as any).mockReturnValue({ schema: { title: 'Test' } });

      const methodOperation = {
        method: 'post',
        operation: {
          operationId: 'test.operation',
          summary: 'Test operation',
          description: 'Test description',
          tags: ['tag1'],
          requestBody: {
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/TestCommand' },
              },
            },
          },
          parameters: [{ $ref: '#/components/parameters/wow.id' }],
        },
      };

      (aggregateResolver as any).commands('/test', methodOperation);
      expect(mockAggregate.commands.size).toBe(1);
      expect(mockAggregate.commands.get('TestCommand')).toBeDefined();
    });

    it('should skip commands for non-existent aggregates', () => {
      (operationIdToCommandName as any).mockReturnValue('TestCommand');
      (extractOkResponse as any).mockReturnValue({
        $ref: '#/components/responses/wow.CommandOk',
      });
      (isReference as any).mockReturnValue(true);
      (extractParameter as any).mockReturnValue({ name: 'id', in: 'path' });
      (keySchema as any).mockReturnValue({ schema: { title: 'Test' } });

      const methodOperation = {
        method: 'post',
        operation: {
          operationId: 'test.operation',
          summary: 'Test operation',
          description: 'Test description',
          tags: ['nonexistent-tag'], // This tag doesn't exist in aggregates
          requestBody: {
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/TestCommand' },
              },
            },
          },
          parameters: [{ $ref: '#/components/parameters/wow.id' }],
        },
      };

      (aggregateResolver as any).commands('/test', methodOperation);
      // Should not throw and should not add commands to any aggregate
      expect(mockAggregate.commands.size).toBe(0);
    });
  });

  describe('state', () => {
    let aggregateResolver: AggregateResolver;
    let mockAggregate: any;

    beforeEach(() => {
      mockAggregate = {
        aggregate: {
          tag: { name: 'tag1' },
          aggregateName: 'TestAggregate',
          contextAlias: 'test',
        },
        commands: new Map(),
        events: new Map(),
      };

      mockTagsToAggregates.mockReturnValue(new Map([['tag1', mockAggregate]]));
      aggregateResolver = new AggregateResolver(mockOpenAPI);
    });

    it('should skip non-snapshot operations', () => {
      const operation = { operationId: 'test.operation' };

      (aggregateResolver as any).state(operation);
      expect(mockAggregate.state).toBeUndefined();
    });

    it('should skip operations without reference response', () => {
      const operation = {
        operationId: 'test.snapshot_state.single',
        tags: ['tag1'],
      };
      (extractOperationOkResponseJsonSchema as any).mockReturnValue({
        type: 'object',
      });
      (isReference as any).mockReturnValue(false);

      (aggregateResolver as any).state(operation);
      expect(mockAggregate.state).toBeUndefined();
    });

    it('should set state for snapshot operations', () => {
      const operation = {
        operationId: 'test.snapshot_state.single',
        tags: ['tag1'],
      };
      const mockStateSchema = { schema: { type: 'object' } };
      (extractOperationOkResponseJsonSchema as any).mockReturnValue({
        $ref: '#/components/schemas/State',
      });
      (isReference as any).mockReturnValue(true);
      (keySchema as any).mockReturnValue(mockStateSchema);

      (aggregateResolver as any).state(operation);
      expect(mockAggregate.state).toBe(mockStateSchema);
    });

    it('should skip state for non-existent aggregates', () => {
      const operation = {
        operationId: 'test.snapshot_state.single',
        tags: ['nonexistent-tag'],
      };
      const mockStateSchema = { schema: { type: 'object' } };
      (extractOperationOkResponseJsonSchema as any).mockReturnValue({
        $ref: '#/components/schemas/State',
      });
      (isReference as any).mockReturnValue(true);
      (keySchema as any).mockReturnValue(mockStateSchema);

      (aggregateResolver as any).state(operation);
      // Should not throw and should not set state on any aggregate
      expect(mockAggregate.state).toBeUndefined();
    });
  });

  describe('events', () => {
    let aggregateResolver: AggregateResolver;
    let mockAggregate: any;

    beforeEach(() => {
      mockAggregate = {
        aggregate: {
          tag: { name: 'tag1' },
          aggregateName: 'TestAggregate',
          contextAlias: 'test',
        },
        commands: new Map(),
        events: new Map(),
      };

      mockTagsToAggregates.mockReturnValue(new Map([['tag1', mockAggregate]]));
      aggregateResolver = new AggregateResolver(mockOpenAPI);
    });

    it('should skip when no components', () => {
      mockOpenAPI.components = undefined;
      const operation = { operationId: 'test.event.list_query' };

      (aggregateResolver as any).events(operation);
      expect(mockAggregate.events.size).toBe(0);
    });

    it('should skip non-event operations', () => {
      const operation = { operationId: 'test.operation' };

      (aggregateResolver as any).events(operation);
      expect(mockAggregate.events.size).toBe(0);
    });

    it('should skip operations with reference response', () => {
      const operation = {
        operationId: 'test.event.list_query',
        tags: ['tag1'],
      };
      (extractOperationOkResponseJsonSchema as any).mockReturnValue({
        $ref: '#/components/schemas/EventStream',
      });
      (isReference as any).mockReturnValue(true);

      (aggregateResolver as any).events(operation);
      expect(mockAggregate.events.size).toBe(0);
    });

    it('should skip operations without reference items', () => {
      const operation = {
        operationId: 'test.event.list_query',
        tags: ['tag1'],
      };
      (extractOperationOkResponseJsonSchema as any).mockReturnValue({
        items: { type: 'object' },
      });
      (isReference as any)
        .mockReturnValueOnce(false)
        .mockReturnValueOnce(false);

      (aggregateResolver as any).events(operation);
      expect(mockAggregate.events.size).toBe(0);
    });

    it('should process event streams', () => {
      const operation = {
        operationId: 'test.event.list_query',
        tags: ['tag1'],
      };
      const mockEventSchema = {
        properties: {
          body: {
            items: {
              anyOf: [
                {
                  title: 'Event1',
                  properties: {
                    name: { const: 'event1' },
                    body: { $ref: '#/components/schemas/EventBody' },
                  },
                },
              ],
            },
          },
        },
      };
      const mockEventBodySchema = { schema: { type: 'object' } };

      (extractOperationOkResponseJsonSchema as any).mockReturnValue({
        items: { $ref: '#/components/schemas/EventStream' },
      });
      (isReference as any).mockReturnValueOnce(false).mockReturnValueOnce(true);
      (extractSchema as any).mockReturnValue(mockEventSchema);
      (keySchema as any).mockReturnValue(mockEventBodySchema);

      (aggregateResolver as any).events(operation);
      expect(mockAggregate.events.size).toBe(1);
      expect(mockAggregate.events.get('event1')).toBeDefined();
    });

    it('should skip events for non-existent aggregates', () => {
      const operation = {
        operationId: 'test.event.list_query',
        tags: ['nonexistent-tag'],
      };
      const mockEventSchema = {
        properties: {
          body: {
            items: {
              anyOf: [
                {
                  title: 'Event1',
                  properties: {
                    name: { const: 'event1' },
                    body: { $ref: '#/components/schemas/EventBody' },
                  },
                },
              ],
            },
          },
        },
      };
      const mockEventBodySchema = { schema: { type: 'object' } };

      (extractOperationOkResponseJsonSchema as any).mockReturnValue({
        items: { $ref: '#/components/schemas/EventStream' },
      });
      (isReference as any).mockReturnValueOnce(false).mockReturnValueOnce(true);
      (extractSchema as any).mockReturnValue(mockEventSchema);
      (keySchema as any).mockReturnValue(mockEventBodySchema);

      (aggregateResolver as any).events(operation);
      // Should not throw and should not add events to any aggregate
      expect(mockAggregate.events.size).toBe(0);
    });
  });

  describe('fields', () => {
    let aggregateResolver: AggregateResolver;
    let mockAggregate: any;

    beforeEach(() => {
      mockAggregate = {
        aggregate: {
          tag: { name: 'tag1' },
          aggregateName: 'TestAggregate',
          contextAlias: 'test',
        },
        commands: new Map(),
        events: new Map(),
      };

      mockTagsToAggregates.mockReturnValue(new Map([['tag1', mockAggregate]]));
      aggregateResolver = new AggregateResolver(mockOpenAPI);
    });

    it('should skip when no components', () => {
      mockOpenAPI.components = undefined;
      const operation = { operationId: 'test.snapshot.count' };

      (aggregateResolver as any).fields(operation);
      expect(mockAggregate.fields).toBeUndefined();
    });

    it('should skip non-count operations', () => {
      const operation = { operationId: 'test.operation' };

      (aggregateResolver as any).fields(operation);
      expect(mockAggregate.fields).toBeUndefined();
    });

    it('should set fields for count operations', () => {
      const operation = {
        operationId: 'test.snapshot.count',
        tags: ['tag1'],
        requestBody: { $ref: '#/components/requestBodies/Condition' },
      };
      const mockConditionSchema = {
        properties: {
          field: { $ref: '#/components/schemas/Field' },
        },
      };
      const mockFieldSchema = { schema: { type: 'string' } };

      (extractRequestBody as any).mockReturnValue({
        content: {
          'application/json': {
            schema: { $ref: '#/components/schemas/Condition' },
          },
        },
      });
      (extractSchema as any)
        .mockReturnValueOnce(mockConditionSchema)
        .mockReturnValueOnce({ type: 'string' });
      (keySchema as any).mockReturnValue(mockFieldSchema);

      (aggregateResolver as any).fields(operation);
      expect(mockAggregate.fields).toBe(mockFieldSchema);
    });

    it('should skip fields for non-existent aggregates', () => {
      const operation = {
        operationId: 'test.snapshot.count',
        tags: ['nonexistent-tag'],
        requestBody: { $ref: '#/components/requestBodies/Condition' },
      };
      const mockConditionSchema = {
        properties: {
          field: { $ref: '#/components/schemas/Field' },
        },
      };
      const mockFieldSchema = { schema: { type: 'string' } };

      (extractRequestBody as any).mockReturnValue({
        content: {
          'application/json': {
            schema: { $ref: '#/components/schemas/Condition' },
          },
        },
      });
      (extractSchema as any)
        .mockReturnValueOnce(mockConditionSchema)
        .mockReturnValueOnce({ type: 'string' });
      (keySchema as any).mockReturnValue(mockFieldSchema);

      (aggregateResolver as any).fields(operation);
      // Should not throw and should not set fields on any aggregate
      expect(mockAggregate.fields).toBeUndefined();
    });
  });
});

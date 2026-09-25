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

import type { Tag } from '@ahoo-wang/fetcher-openapi';
import { ResourceAttributionPathSpec } from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import {
  aggregatedTypeNames,
  contextAliasOf,
  IMPORT_WOW_LEGACY_PATH,
  IMPORT_WOW_PATH,
  inferPathSpecType,
  isAliasAggregate,
  isWowSchema,
  operationIdToCommandName,
  OWNER_PATH_PREFIX,
  tagToAggregate,
  TENANT_PATH_PREFIX,
  WOW_LEGACY_TYPES,
  WOW_TYPE_MAPPING,
  wowTypeOf,
} from '../../src/wow/conventions';

describe('Wow conventions: tags and operation ids', () => {
  describe('isAliasAggregate', () => {
    it('should return [contextAlias, aggregateName] for valid alias aggregate pattern', () => {
      expect(isAliasAggregate('context.aggregate')).toEqual([
        'context',
        'aggregate',
      ]);
      expect(isAliasAggregate('user.User')).toEqual(['user', 'User']);
    });

    it('should return null for invalid patterns', () => {
      expect(isAliasAggregate('single')).toBeNull();
      expect(isAliasAggregate('too.many.parts.here')).toBeNull();
      expect(isAliasAggregate('')).toBeNull();
      expect(isAliasAggregate('context.')).toBeNull();
      expect(isAliasAggregate('.aggregate')).toBeNull();
    });
  });

  describe('tagToAggregate', () => {
    it('should convert valid tag to TagAliasAggregate', () => {
      const tag: Tag = {
        name: 'context.aggregate',
        description: 'Test aggregate',
      };

      const result = tagToAggregate(tag);
      expect(result).toEqual({
        tag,
        contextAlias: 'context',
        aggregateName: 'aggregate',
      });
    });

    it('should return null for invalid tag name', () => {
      const tag: Tag = {
        name: 'invalid',
        description: 'Invalid tag',
      };

      expect(tagToAggregate(tag)).toBeNull();
    });
  });

  describe('operationIdToCommandName', () => {
    it('should extract command name from valid operation ID', () => {
      expect(operationIdToCommandName('context.aggregate.command')).toBe(
        'command',
      );
      expect(operationIdToCommandName('user.User.create')).toBe('create');
    });

    it('should return null for invalid operation IDs', () => {
      expect(operationIdToCommandName(undefined)).toBeNull();
      expect(operationIdToCommandName('')).toBeNull();
      expect(operationIdToCommandName('single')).toBeNull();
      expect(operationIdToCommandName('context.aggregate')).toBeNull();
      expect(
        operationIdToCommandName('too.many.parts.in.operation.id'),
      ).toBeNull();
    });
  });
});

describe('Wow conventions: schemas', () => {
  const aggregated = new Set(aggregatedTypeNames('CartState'));

  it('derives the aggregated types of a state', () => {
    expect([...aggregated]).toEqual([
      'CartStateMaterializedSnapshot',
      'CartStateMaterializedSnapshotPagedList',
      'CartStateMaterializedSnapshotCursorPage',
      'CartStateMaterializedSnapshotServerSentEventNonNullData',
      'CartStatePagedList',
      'CartStateServerSentEventNonNullData',
      'CartStateSnapshot',
      'CartStateStateEvent',
    ]);
  });

  it.each([
    ['wow.api.BindingError', 'BindingError', true],
    ['wow.api.query.PagedList', 'PagedList', true],
    ['wow.api.query.ItemPagedList', 'ItemPagedList', false],
    ['wow.api.query.OperatorStringMap', 'OperatorStringMap', false],
    ['example.cart.CartAggregatedCondition', 'CartAggregatedCondition', true],
    ['example.cart.CartAggregatedDomainEventStream', 'X', true],
    ['example.cart.CartAggregatedDomainEventStreamPagedList', 'X', true],
    ['example.cart.CartAggregatedDomainEventStreamCursorPage', 'X', true],
    [
      'example.cart.CartAggregatedDomainEventStreamServerSentEventNonNullData',
      'X',
      true,
    ],
    ['example.cart.CartAggregatedListQuery', 'X', true],
    ['example.cart.CartAggregatedPagedQuery', 'X', true],
    ['example.cart.CartAggregatedSingleQuery', 'X', true],
    ['example.cart.CartStateSnapshot', 'CartStateSnapshot', true],
    ['example.cart.CartState', 'CartState', false],
    ['example.cart.AddCartItem', 'AddCartItem', false],
  ])('tells whether %s is Wow’s own: %s → %s', (key, name, expected) => {
    expect(isWowSchema(key, () => name, aggregated)).toBe(expected);
  });

  it('reads the model name only when the key does not decide', () => {
    let reads = 0;
    const name = () => {
      reads++;
      return 'Item';
    };
    isWowSchema('wow.api.BindingError', name, aggregated);
    isWowSchema('wow.api.query.ItemPagedList', name, aggregated);
    expect(reads).toBe(0);
    isWowSchema('shop.Item', name, aggregated);
    expect(reads).toBe(1);
  });

  it('reads the bounded context a document names', () => {
    const info = { title: 't', version: '1' };
    expect(
      contextAliasOf({
        openapi: '3.0.1',
        info: { ...info, 'x-wow-context-alias': 'shop' },
        paths: {},
      }),
    ).toBe('shop');
    expect(
      contextAliasOf({ openapi: '3.0.1', info, paths: {} }),
    ).toBeUndefined();
  });
});

describe('Wow conventions: resource attribution', () => {
  it('uses the route prefixes of wow-client', () => {
    expect(TENANT_PATH_PREFIX).toBe(ResourceAttributionPathSpec.TENANT);
    expect(OWNER_PATH_PREFIX).toBe(ResourceAttributionPathSpec.OWNER);
  });

  describe('inferPathSpecType', () => {
    it('should return NONE when no commands have tenant or owner specs', () => {
      const aggregateDefinition = {
        commands: [{ path: '/api/users' }, { path: '/api/products' }],
      };

      const result = inferPathSpecType(aggregateDefinition as any);
      expect(result).toBe('ResourceAttributionPathSpec.NONE');
    });

    it('should return TENANT when most commands have tenant spec', () => {
      const aggregateDefinition = {
        commands: [
          { path: ResourceAttributionPathSpec.TENANT + '/users' },
          { path: ResourceAttributionPathSpec.TENANT + '/products' },
          { path: ResourceAttributionPathSpec.OWNER + '/orders' },
        ],
      };

      const result = inferPathSpecType(aggregateDefinition as any);
      expect(result).toBe('ResourceAttributionPathSpec.TENANT');
    });

    it('should return OWNER when most commands have owner spec', () => {
      const aggregateDefinition = {
        commands: [
          { path: ResourceAttributionPathSpec.OWNER + '/users' },
          { path: ResourceAttributionPathSpec.OWNER + '/products' },
          { path: ResourceAttributionPathSpec.TENANT + '/orders' },
        ],
      };

      const result = inferPathSpecType(aggregateDefinition as any);
      expect(result).toBe('ResourceAttributionPathSpec.OWNER');
    });

    it('should return OWNER when equal number of tenant and owner specs', () => {
      const aggregateDefinition = {
        commands: [
          { path: ResourceAttributionPathSpec.TENANT + '/users' },
          { path: ResourceAttributionPathSpec.OWNER + '/products' },
        ],
      };

      const result = inferPathSpecType(aggregateDefinition as any);
      expect(result).toBe('ResourceAttributionPathSpec.OWNER');
    });

    it('should return TENANT when only tenant specs are present', () => {
      const aggregateDefinition = {
        commands: [
          { path: ResourceAttributionPathSpec.TENANT + '/users' },
          { path: ResourceAttributionPathSpec.TENANT + '/products' },
        ],
      };

      const result = inferPathSpecType(aggregateDefinition as any);
      expect(result).toBe('ResourceAttributionPathSpec.TENANT');
    });

    it('should return OWNER when only owner specs are present', () => {
      const aggregateDefinition = {
        commands: [
          { path: ResourceAttributionPathSpec.OWNER + '/users' },
          { path: ResourceAttributionPathSpec.OWNER + '/products' },
        ],
      };

      const result = inferPathSpecType(aggregateDefinition as any);
      expect(result).toBe('ResourceAttributionPathSpec.OWNER');
    });
  });
});

describe('Wow conventions: the wow-client types', () => {
  describe('IMPORT_WOW_PATH', () => {
    it('should be defined', () => {
      expect(IMPORT_WOW_PATH).toBeDefined();
    });

    it('should have the correct value', () => {
      expect(IMPORT_WOW_PATH).toBe('@ahoo-wang/wow-client');
    });
  });

  describe('IMPORT_WOW_LEGACY_PATH', () => {
    it('names the Condition subpath of wow-client', () => {
      expect(IMPORT_WOW_LEGACY_PATH).toBe('@ahoo-wang/wow-client/legacy');
    });

    it('covers exactly the mapped Condition API types', () => {
      expect([...WOW_LEGACY_TYPES].sort()).toEqual([
        'Condition',
        'ConditionOptions',
        'ListQuery',
        'Operator',
        'PagedQuery',
      ]);
      for (const name of WOW_LEGACY_TYPES) {
        expect(Object.values(WOW_TYPE_MAPPING)).toContain(name);
      }
    });
  });

  describe('WOW_TYPE_MAPPING', () => {
    it('should be defined', () => {
      expect(WOW_TYPE_MAPPING).toBeDefined();
    });

    it('should have the correct mappings', () => {
      expect(WOW_TYPE_MAPPING['wow.command.CommandResult']).toBe(
        'CommandResult',
      );
      expect(WOW_TYPE_MAPPING['wow.command.CommandResultArray']).toBe(
        'CommandResultArray',
      );
      expect(WOW_TYPE_MAPPING['wow.MessageHeaderSqlType']).toBe(
        'MessageHeaderSqlType',
      );
      expect(WOW_TYPE_MAPPING['wow.api.BindingError']).toBe('BindingError');
      expect(WOW_TYPE_MAPPING['wow.api.DefaultErrorInfo']).toBe('ErrorInfo');
      expect(WOW_TYPE_MAPPING['wow.api.RecoverableType']).toBe(
        'RecoverableType',
      );
      expect(WOW_TYPE_MAPPING['wow.api.command.DefaultDeleteAggregate']).toBe(
        'DeleteAggregate',
      );
      expect(WOW_TYPE_MAPPING['wow.api.command.DefaultRecoverAggregate']).toBe(
        'RecoverAggregate',
      );
      expect(WOW_TYPE_MAPPING['wow.api.messaging.FunctionInfoData']).toBe(
        'FunctionInfo',
      );
      expect(WOW_TYPE_MAPPING['wow.api.messaging.FunctionKind']).toBe(
        'FunctionKind',
      );
      expect(WOW_TYPE_MAPPING['wow.api.modeling.AggregateId']).toBe(
        'AggregateId',
      );
      expect(WOW_TYPE_MAPPING['wow.api.query.Condition']).toBe('Condition');
      expect(WOW_TYPE_MAPPING['wow.api.query.ConditionOptions']).toBe(
        'ConditionOptions',
      );
      expect(WOW_TYPE_MAPPING['wow.api.query.ListQuery']).toBe('ListQuery');
      expect(WOW_TYPE_MAPPING['wow.api.query.Operator']).toBe('Operator');
      expect(WOW_TYPE_MAPPING['wow.api.query.PagedQuery']).toBe('PagedQuery');
      expect(WOW_TYPE_MAPPING['wow.api.query.Pagination']).toBe('Pagination');
      expect(WOW_TYPE_MAPPING['wow.api.query.Projection']).toBe('Projection');
      expect(WOW_TYPE_MAPPING['wow.api.query.Sort']).toBe('FieldSort');
      expect(WOW_TYPE_MAPPING['wow.api.query.Sort.Direction']).toBe(
        'SortDirection',
      );
      expect(WOW_TYPE_MAPPING['wow.command.CommandStage']).toBe('CommandStage');
      expect(WOW_TYPE_MAPPING['wow.command.SimpleWaitSignal']).toBe(
        'WaitSignal',
      );
      expect(WOW_TYPE_MAPPING['wow.configuration.Aggregate']).toBe('Aggregate');
      expect(WOW_TYPE_MAPPING['wow.configuration.BoundedContext']).toBe(
        'BoundedContext',
      );
      expect(WOW_TYPE_MAPPING['wow.configuration.WowMetadata']).toBe(
        'WowMetadata',
      );
      expect(WOW_TYPE_MAPPING['wow.modeling.DomainEvent']).toBe('DomainEvent');
      expect(WOW_TYPE_MAPPING['wow.openapi.BatchResult']).toBe('BatchResult');
      expect(WOW_TYPE_MAPPING['wow.messaging.CompensationTarget']).toBe(
        'CompensationTarget',
      );
    });

    it('should have the correct number of mappings', () => {
      expect(Object.keys(WOW_TYPE_MAPPING).length).toBe(31);
    });
  });
  describe('wowTypeOf', () => {
    it('maps a Wow schema to its wow-client type and module', () => {
      expect(wowTypeOf('wow.api.BindingError')).toEqual({
        name: 'BindingError',
        path: IMPORT_WOW_PATH,
      });
      expect(wowTypeOf('wow.api.query.Condition')).toEqual({
        name: 'Condition',
        path: IMPORT_WOW_LEGACY_PATH,
      });
      expect(wowTypeOf('example.cart.CartState')).toBeUndefined();
    });

    it('maps a query that carries a filter to the filter model', () => {
      const filter = { filter: {} };
      expect(wowTypeOf('wow.api.query.ListQuery', filter)).toEqual({
        name: 'FilterListQuery',
        path: IMPORT_WOW_PATH,
      });
      expect(wowTypeOf('wow.api.query.PagedQuery', filter)).toEqual({
        name: 'FilterPagedQuery',
        path: IMPORT_WOW_PATH,
      });
      expect(wowTypeOf('wow.api.query.ListQuery', {})).toEqual({
        name: 'ListQuery',
        path: IMPORT_WOW_LEGACY_PATH,
      });
      expect(wowTypeOf('example.Query', filter)).toBeUndefined();
    });
  });
});

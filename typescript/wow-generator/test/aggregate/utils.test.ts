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
import {
  isAliasAggregate,
  tagToAggregate,
  tagsToAggregates,
  operationIdToCommandName,
} from '@/aggregate/utils.ts';
import { Tag } from '@ahoo-wang/fetcher-openapi';

describe('aggregate utils', () => {
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

  describe('tagsToAggregates', () => {
    it('should convert array of valid tags to aggregates map', () => {
      const tags: Tag[] = [
        { name: 'context1.aggregate1', description: 'Aggregate 1' },
        { name: 'context2.aggregate2', description: 'Aggregate 2' },
      ];

      const result = tagsToAggregates(tags);
      expect(result.size).toBe(2);
      expect(result.get('context1.aggregate1')).toEqual({
        aggregate: {
          tag: tags[0],
          contextAlias: 'context1',
          aggregateName: 'aggregate1',
        },
        commands: new Map(),
        events: new Map(),
      });
      expect(result.get('context2.aggregate2')).toEqual({
        aggregate: {
          tag: tags[1],
          contextAlias: 'context2',
          aggregateName: 'aggregate2',
        },
        commands: new Map(),
        events: new Map(),
      });
    });

    it('should filter out invalid tags', () => {
      const tags: Tag[] = [
        { name: 'context.aggregate', description: 'Valid' },
        { name: 'invalid', description: 'Invalid' },
      ];

      const result = tagsToAggregates(tags);
      expect(result.size).toBe(1);
      expect(result.has('context.aggregate')).toBe(true);
      expect(result.has('invalid')).toBe(false);
    });

    it('should return empty map for undefined tags', () => {
      const result = tagsToAggregates(undefined);
      expect(result.size).toBe(0);
    });

    it('should return empty map for empty tags array', () => {
      const result = tagsToAggregates([]);
      expect(result.size).toBe(0);
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

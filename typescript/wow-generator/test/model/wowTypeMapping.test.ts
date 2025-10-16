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
import { IMPORT_WOW_PATH, WOW_TYPE_MAPPING } from '../../src/model';

describe('wowTypeMapping', () => {
  describe('IMPORT_WOW_PATH', () => {
    it('should be defined', () => {
      expect(IMPORT_WOW_PATH).toBeDefined();
    });

    it('should have the correct value', () => {
      expect(IMPORT_WOW_PATH).toBe('@ahoo-wang/fetcher-wow');
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
      expect(Object.keys(WOW_TYPE_MAPPING).length).toBe(28);
    });
  });
});

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

/** Import path for the WOW framework types */
export const IMPORT_WOW_PATH = '@ahoo-wang/fetcher-wow';

/** Mapping of OpenAPI schema keys to WOW framework types */
export const WOW_TYPE_MAPPING = {
  'wow.command.CommandResult': 'CommandResult',
  'wow.command.CommandResultArray': 'CommandResultArray',
  'wow.MessageHeaderSqlType': 'MessageHeaderSqlType',
  'wow.api.BindingError': 'BindingError',
  'wow.api.DefaultErrorInfo': 'ErrorInfo',
  'wow.api.RecoverableType': 'RecoverableType',
  'wow.api.command.DefaultDeleteAggregate': 'DeleteAggregate',
  'wow.api.command.DefaultRecoverAggregate': 'RecoverAggregate',
  'wow.api.messaging.FunctionInfoData': 'FunctionInfo',
  'wow.api.messaging.FunctionKind': 'FunctionKind',
  'wow.api.modeling.AggregateId': 'AggregateId',
  'wow.api.query.Condition': 'Condition',
  'wow.api.query.ConditionOptions': 'ConditionOptions',
  'wow.api.query.ListQuery': 'ListQuery',
  'wow.api.query.Operator': 'Operator',
  'wow.api.query.PagedQuery': 'PagedQuery',
  'wow.api.query.Pagination': 'Pagination',
  'wow.api.query.Projection': 'Projection',
  'wow.api.query.Sort': 'FieldSort',
  'wow.api.query.Sort.Direction': 'SortDirection',
  'wow.command.CommandStage': 'CommandStage',
  'wow.command.SimpleWaitSignal': 'WaitSignal',
  'wow.configuration.Aggregate': 'Aggregate',
  'wow.configuration.BoundedContext': 'BoundedContext',
  'wow.configuration.WowMetadata': 'WowMetadata',
  'wow.modeling.DomainEvent': 'DomainEvent',
  'wow.openapi.BatchResult': 'BatchResult',
  'wow.messaging.CompensationTarget': 'CompensationTarget',
};

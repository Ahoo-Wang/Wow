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

/**
 * What the filter kernel is exercised against: one field of every built-in
 * kind, a fixed clock and zone for the relative values to resolve against,
 * and the two shorthands every suite writes conditions with. Validation,
 * compilation, time resolution and tree editing all start here.
 */

import type { FieldDefinition, FilterTree } from '../../src/index.js';

export const filterFields: FieldDefinition[] = [
  { name: 'id', label: 'Order', kind: 'string' },
  { name: 'amount', label: 'Amount', kind: 'number' },
  { name: 'paid', label: 'Paid', kind: 'boolean' },
  {
    name: 'status',
    label: 'Status',
    kind: 'enum',
    options: [
      { value: 'PENDING', label: 'Pending' },
      { value: 'SHIPPED', label: 'Shipped' },
    ],
  },
  { name: 'createdAt', label: 'Created', kind: 'datetime' },
  {
    name: 'customer',
    label: 'Customer',
    kind: 'reference',
    remote: 'customers',
  },
];

/** The clock and zone a relative or preset value resolves against. */
export const filterContext = {
  now: new Date('2026-09-16T10:30:00.000Z'),
  timeZone: 'UTC',
};

/** The root group, which is an AND of whatever it was given. */
export function andTree(...children: FilterTree['children']): FilterTree {
  return { op: 'and', children };
}

/** The codes of the issues that stop a config, in the order they were found. */
export function errorCodes(
  issues: { severity: string; code: string }[],
): string[] {
  return issues.filter(i => i.severity === 'error').map(i => i.code);
}

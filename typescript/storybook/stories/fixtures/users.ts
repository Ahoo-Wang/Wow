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

import type { PagedList } from '@ahoo-wang/wow-client';

export interface FixtureUser {
  id: string;
  name: string;
  role: 'Admin' | 'Member';
  active: boolean;
  balance: number;
  createdAt: string;
}

export const fixtureUsers: FixtureUser[] = [
  {
    id: 'u-ada',
    name: 'Ada',
    role: 'Admin',
    active: true,
    balance: 1250,
    createdAt: '2026-01-15T09:30:00.000Z',
  },
  {
    id: 'u-lin',
    name: 'Lin',
    role: 'Member',
    active: false,
    balance: 0,
    createdAt: '2026-01-16T10:00:00.000Z',
  },
];

export const fixturePagedUsers: PagedList<FixtureUser> = {
  list: fixtureUsers,
  total: fixtureUsers.length,
};

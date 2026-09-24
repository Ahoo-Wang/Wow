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

import type {
  AvailableFilterGroup,
  FieldDefinition,
  ViewColumn,
  ViewDefinition,
  ViewState,
} from '@ahoo-wang/fetcher-viewer';
import type { PagedList } from '@ahoo-wang/fetcher-wow';
import { all, eq } from '@ahoo-wang/fetcher-wow';

export interface FixtureViewerUser {
  id: string;
  name: string;
  role: 'Admin' | 'Member';
  active: boolean;
  balance: number;
  createdAt: string;
  avatar: string;
}

export const fixtureAvatar =
  'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" width="64" height="64"%3E%3Crect width="64" height="64" fill="%231675d1"/%3E%3Ctext x="32" y="40" text-anchor="middle" font-size="24" fill="white"%3EA%3C/text%3E%3C/svg%3E';

export const fixtureViewerUsers: FixtureViewerUser[] = [
  {
    id: 'u-ada',
    name: 'Ada',
    role: 'Admin',
    active: true,
    balance: 1250,
    createdAt: '2026-01-15T09:30:00.000Z',
    avatar: fixtureAvatar,
  },
  {
    id: 'u-lin',
    name: 'Lin',
    role: 'Member',
    active: false,
    balance: 0,
    createdAt: '2026-01-16T10:00:00.000Z',
    avatar: fixtureAvatar,
  },
];

export const fixturePagedUsers: PagedList<FixtureViewerUser> = {
  list: fixtureViewerUsers,
  total: fixtureViewerUsers.length,
};

export const emptyPagedUsers: PagedList<FixtureViewerUser> = {
  list: [],
  total: 0,
};

export const fixtureActiveRows: readonly FixtureViewerUser[] = [
  fixtureViewerUsers[0],
];

export const fixtureViewerError = new Error('Unable to load users');

export const fixtureFields: FieldDefinition[] = [
  { name: 'id', label: 'ID', type: 'text', primaryKey: true, sorter: true },
  {
    name: 'avatar',
    label: 'Avatar',
    type: 'avatar',
    primaryKey: false,
  },
  {
    name: 'name',
    label: 'Name',
    type: 'text',
    primaryKey: false,
    sorter: true,
  },
  { name: 'role', label: 'Role', type: 'tag', primaryKey: false },
  { name: 'active', label: 'Active', type: 'text', primaryKey: false },
  {
    name: 'balance',
    label: 'Balance',
    type: 'currency',
    primaryKey: false,
    sorter: true,
  },
  {
    name: 'createdAt',
    label: 'Created',
    type: 'datetime',
    primaryKey: false,
    sorter: true,
  },
];

export const fixtureColumns: ViewColumn[] = fixtureFields.map(field => ({
  key: field.name,
  name: field.name,
  fixed: field.primaryKey,
  hidden: false,
}));

export const fixtureAvailableFilters: AvailableFilterGroup[] = [
  {
    label: 'User',
    filters: [
      {
        key: 'name',
        field: { name: 'name', label: 'Name' },
        component: 'text',
      },
      {
        key: 'role',
        field: { name: 'role', label: 'Role' },
        component: 'select',
        value: {
          options: [
            { label: 'Admin', value: 'Admin' },
            { label: 'Member', value: 'Member' },
          ],
        },
      },
      {
        key: 'active',
        field: { name: 'active', label: 'Active' },
        component: 'bool',
      },
      {
        key: 'createdAt',
        field: { name: 'createdAt', label: 'Created' },
        component: 'datetime',
      },
    ],
  },
];

export const fixtureViewerDefinition: ViewDefinition = {
  id: 'users',
  name: 'Users',
  fields: fixtureFields,
  availableFilters: fixtureAvailableFilters,
  dataUrl: '/users/paged',
  countUrl: '/users/count',
};

export const fixtureDefaultView: ViewState = {
  id: 'all-users',
  name: 'All users',
  definitionId: fixtureViewerDefinition.id,
  type: 'PERSONAL',
  source: 'SYSTEM',
  isDefault: true,
  filters: [],
  columns: fixtureColumns,
  tableSize: 'middle',
  pageSize: 10,
  condition: all(),
  sorter: [],
};

export const fixtureAdminView: ViewState = {
  ...fixtureDefaultView,
  id: 'admin-users',
  name: 'Admins',
  source: 'CUSTOM',
  isDefault: false,
  condition: eq('role', 'Admin'),
};

export const fixtureViews: ViewState[] = [fixtureDefaultView, fixtureAdminView];

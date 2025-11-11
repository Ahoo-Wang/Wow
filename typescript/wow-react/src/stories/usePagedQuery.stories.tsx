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

import type { Meta, StoryObj } from '@storybook/react';
import React from 'react';
import { usePagedQuery } from '../usePagedQuery';
import { PagedQuery, eq, all } from '@ahoo-wang/fetcher-wow';
import {
  Button,
  Card,
  Typography,
  Space,
  Alert,
  List,
  Pagination,
  Tag,
} from 'antd';

const { Text } = Typography;

interface User {
  id: number;
  name: string;
  email: string;
  status: 'active' | 'inactive';
}

// Mock API function
const mockFetchPagedUsers = async (
  query: PagedQuery,
): Promise<{ total: number; list: User[] }> => {
  // Simulate API delay
  await new Promise(resolve => setTimeout(resolve, 1000));

  // Mock data
  const allUsers: User[] = [
    { id: 1, name: 'John Doe', email: 'john@example.com', status: 'active' },
    { id: 2, name: 'Jane Smith', email: 'jane@example.com', status: 'active' },
    {
      id: 3,
      name: 'Bob Johnson',
      email: 'bob@example.com',
      status: 'inactive',
    },
    {
      id: 4,
      name: 'Alice Brown',
      email: 'alice@example.com',
      status: 'active',
    },
    {
      id: 5,
      name: 'Charlie Wilson',
      email: 'charlie@example.com',
      status: 'inactive',
    },
    {
      id: 6,
      name: 'Diana Prince',
      email: 'diana@example.com',
      status: 'active',
    },
    { id: 7, name: 'Eve Adams', email: 'eve@example.com', status: 'inactive' },
    {
      id: 8,
      name: 'Frank Miller',
      email: 'frank@example.com',
      status: 'active',
    },
  ];

  // Apply condition filtering
  let filteredUsers = allUsers;
  if (query.condition && query.condition.field && query.condition.operator) {
    if (query.condition.field === 'status') {
      filteredUsers = allUsers.filter(
        user => user.status === query.condition!.value,
      );
    }
  }

  // Apply pagination
  const page = query.pagination?.index || 1;
  const size = query.pagination?.size || 10;
  const startIndex = (page - 1) * size;
  const endIndex = startIndex + size;
  const paginatedUsers = filteredUsers.slice(startIndex, endIndex);

  return {
    total: filteredUsers.length,
    list: paginatedUsers,
  };
};

// Component that uses usePagedQuery
interface PagedQueryDemoProps {
  filterStatus?: string;
  pageSize?: number;
  autoExecute?: boolean;
}

function PagedQueryDemo({
  filterStatus = 'all',
  pageSize = 3,
  autoExecute = true,
}: PagedQueryDemoProps) {
  const [currentPage, setCurrentPage] = React.useState(1);

  const getInitialQuery = (): PagedQuery => {
    const baseQuery: PagedQuery = {
      condition: filterStatus === 'all' ? all() : eq('status', filterStatus),
      pagination: { index: currentPage, size: pageSize },
    };
    return baseQuery;
  };

  const { loading, result, error, execute, setQuery } = usePagedQuery<User>({
    initialQuery: getInitialQuery(),
    execute: mockFetchPagedUsers,
    autoExecute,
  });

  const handleFilterChange = (status: string) => {
    setCurrentPage(1);
    setQuery({
      condition: status === 'all' ? all() : eq('status', status),
      pagination: { index: 1, size: pageSize },
    });
  };

  const handlePageChange = (page: number) => {
    setCurrentPage(page);
    setQuery({
      ...getInitialQuery(),
      pagination: { index: page, size: pageSize },
    });
  };

  const handleManualExecute = () => {
    execute();
  };

  return (
    <Card title="usePagedQuery Demo" style={{ width: 600 }}>
      <Space direction="vertical" style={{ width: '100%' }}>
        <div>
          <Text strong>Filter by Status: </Text>
          <Space>
            <Button
              size="small"
              type={filterStatus === 'all' ? 'primary' : 'default'}
              onClick={() => handleFilterChange('all')}
            >
              All
            </Button>
            <Button
              size="small"
              type={filterStatus === 'active' ? 'primary' : 'default'}
              onClick={() => handleFilterChange('active')}
            >
              Active
            </Button>
            <Button
              size="small"
              type={filterStatus === 'inactive' ? 'primary' : 'default'}
              onClick={() => handleFilterChange('inactive')}
            >
              Inactive
            </Button>
          </Space>
        </div>

        {!autoExecute && (
          <Button onClick={handleManualExecute} type="primary">
            Execute Paged Query
          </Button>
        )}

        {loading && <Text type="warning">Loading users...</Text>}

        {result && (
          <div>
            <Text strong>Users (Total: {result.total}):</Text>
            <List
              size="small"
              dataSource={result.list}
              renderItem={user => (
                <List.Item>
                  <Space>
                    <Text>{user.name}</Text>
                    <Text type="secondary">({user.email})</Text>
                    <Tag color={user.status === 'active' ? 'green' : 'red'}>
                      {user.status}
                    </Tag>
                  </Space>
                </List.Item>
              )}
            />
            <Pagination
              current={currentPage}
              pageSize={pageSize}
              total={result.total}
              onChange={handlePageChange}
              size="small"
              showSizeChanger={false}
            />
          </div>
        )}

        {error && (
          <Alert
            message="Error"
            description={error.message}
            type="error"
            showIcon
          />
        )}

        <Text type="secondary">
          This demonstrates the usePagedQuery hook for querying paged data with
          conditions and pagination.
        </Text>
      </Space>
    </Card>
  );
}

const meta: Meta<typeof PagedQueryDemo> = {
  title: 'React/Hooks/usePagedQuery',
  component: PagedQueryDemo,
  parameters: {
    layout: 'centered',
    docs: {
      description: {
        component:
          'A React hook for querying paged data with conditions, projection, pagination, and sorting.',
      },
    },
  },
  tags: ['autodocs'],
  argTypes: {
    filterStatus: {
      control: { type: 'select' },
      options: ['all', 'active', 'inactive'],
      description: 'Status filter for users',
    },
    pageSize: {
      control: { type: 'number', min: 1, max: 10 },
      description: 'Number of items per page',
    },
    autoExecute: {
      control: 'boolean',
      description:
        'Whether to automatically execute the query when parameters change',
    },
  },
};

export default meta;
type Story = StoryObj<typeof meta>;

export const AllUsers: Story = {
  args: {
    filterStatus: 'all',
    pageSize: 3,
    autoExecute: true,
  },
};

export const ActiveUsers: Story = {
  args: {
    filterStatus: 'active',
    pageSize: 2,
    autoExecute: true,
  },
};

export const ManualExecute: Story = {
  args: {
    filterStatus: 'inactive',
    pageSize: 2,
    autoExecute: false,
  },
};

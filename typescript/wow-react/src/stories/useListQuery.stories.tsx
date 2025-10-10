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
import { useListQuery } from '../useListQuery';
import { ListQuery } from '@ahoo-wang/fetcher-wow';
import { Button, Card, Typography, Space, Alert, List, Tag } from 'antd';

const { Text } = Typography;

interface User {
  id: number;
  name: string;
  email: string;
  status: 'active' | 'inactive';
}

// Mock API function
const mockFetchUsers = async (query: ListQuery): Promise<User[]> => {
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
  ];

  // Simple filtering based on condition (simplified)
  let filteredUsers = allUsers;
  if (query.condition && Array.isArray(query.condition)) {
    const statusCondition = query.condition.find(
      (c: any) => c.field === 'status',
    );
    if (statusCondition) {
      filteredUsers = allUsers.filter(
        user => user.status === statusCondition.value,
      );
    }
  }

  return filteredUsers;
};

// Component that uses useListQuery
interface ListQueryDemoProps {
  filterStatus?: string;
  autoExecute?: boolean;
}

function ListQueryDemo({
                         filterStatus = 'all',
                         autoExecute = true,
                       }: ListQueryDemoProps) {
  const getInitialQuery = (): ListQuery => {
    if (filterStatus === 'all') {
      return { condition: [] } as any;
    }
    return {
      condition: [
        { field: 'status', operator: 'eq' as any, value: filterStatus },
      ],
    } as any;
  };

  const { loading, result, error, execute, setQuery } = useListQuery<User>({
    initialQuery: getInitialQuery(),
    execute: mockFetchUsers,
    autoExecute,
  });

  const handleFilterChange = (status: string) => {
    if (status === 'all') {
      setQuery({ condition: [] } as any);
    } else {
      setQuery({
        condition: [{ field: 'status', operator: 'eq' as any, value: status }],
      } as any);
    }
  };

  const handleManualExecute = () => {
    execute();
  };

  return (
    <Card title="useListQuery Demo" style={{ width: 600 }}>
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
            Execute List Query
          </Button>
        )}

        {loading && <Text type="warning">Loading users...</Text>}

        {result && (
          <div>
            <Text strong>Users ({result.length}):</Text>
            <List
              size="small"
              dataSource={result}
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
          This demonstrates the useListQuery hook for querying list data with
          conditions.
        </Text>
      </Space>
    </Card>
  );
}

const meta: Meta<typeof ListQueryDemo> = {
  title: 'React/Hooks/useListQuery',
  component: ListQueryDemo,
  parameters: {
    layout: 'centered',
    docs: {
      description: {
        component:
          'A React hook for querying list data with conditions, projection, and sorting.',
      },
    },
  },
  tags: ['autodocs'],
  argTypes: {
    filterStatus: {
      control: { type: 'select' },
      options: ['all', 'active', 'inactive'],
      description: 'Initial status filter',
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
    autoExecute: true,
  },
};

export const ActiveUsers: Story = {
  args: {
    filterStatus: 'active',
    autoExecute: true,
  },
};

export const ManualExecute: Story = {
  args: {
    filterStatus: 'inactive',
    autoExecute: false,
  },
};

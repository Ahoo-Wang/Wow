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
import { useCountQuery } from '../useCountQuery';
import { eq, all } from '@ahoo-wang/fetcher-wow';
import { Button, Card, Typography, Space, Alert } from 'antd';

const { Text } = Typography;

// Mock API function
const mockFetchUserCount = async (condition: any): Promise<number> => {
  // Simulate API delay
  await new Promise(resolve => setTimeout(resolve, 1000));

  // Mock data
  const allUsers = [
    { id: 1, status: 'active' },
    { id: 2, status: 'active' },
    { id: 3, status: 'inactive' },
    { id: 4, status: 'active' },
    { id: 5, status: 'inactive' },
  ];

  // Apply condition filtering
  let filteredUsers = allUsers;
  if (condition && condition.field && condition.operator) {
    if (condition.field === 'status') {
      filteredUsers = allUsers.filter(user => user.status === condition.value);
    }
  }

  return filteredUsers.length;
};

// Component that uses useCountQuery
interface CountQueryDemoProps {
  filterStatus?: string;
  autoExecute?: boolean;
}

function CountQueryDemo({
  filterStatus = 'all',
  autoExecute = true,
}: CountQueryDemoProps) {
  const getInitialQuery = () => {
    return filterStatus === 'all' ? all() : eq('status', filterStatus);
  };

  const { loading, result, error, execute, setQuery } = useCountQuery({
    initialQuery: getInitialQuery(),
    execute: mockFetchUserCount,
    autoExecute,
  });

  const handleFilterChange = (status: string) => {
    setQuery(status === 'all' ? all() : eq('status', status));
  };

  const handleManualExecute = () => {
    execute();
  };

  return (
    <Card title="useCountQuery Demo" style={{ width: 400 }}>
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
            Execute Count Query
          </Button>
        )}

        {loading && <Text type="warning">Counting users...</Text>}

        {result !== undefined && (
          <Alert
            message="User Count"
            description={`Total users: ${result}`}
            type="success"
            showIcon
          />
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
          This demonstrates the useCountQuery hook for querying count data with
          conditions.
        </Text>
      </Space>
    </Card>
  );
}

const meta: Meta<typeof CountQueryDemo> = {
  title: 'React/Hooks/useCountQuery',
  component: CountQueryDemo,
  parameters: {
    layout: 'centered',
    docs: {
      description: {
        component: 'A React hook for querying count data with conditions.',
      },
    },
  },
  tags: ['autodocs'],
  argTypes: {
    filterStatus: {
      control: { type: 'select' },
      options: ['all', 'active', 'inactive'],
      description: 'Status filter for counting users',
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

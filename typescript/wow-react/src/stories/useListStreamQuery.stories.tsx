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
import { useListStreamQuery } from '../useListStreamQuery';
import { ListQuery, eq, all } from '@ahoo-wang/fetcher-wow';
import { Button, Card, Typography, Space, Alert, List, Tag } from 'antd';

const { Text } = Typography;

interface User {
  id: number;
  name: string;
  email: string;
  status: 'active' | 'inactive';
}

// Mock streaming API function
const mockFetchUserStream = async (
  query: ListQuery,
): Promise<ReadableStream<any>> => {
  // Simulate API delay
  await new Promise(resolve => setTimeout(resolve, 500));

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

  // Apply condition filtering
  let filteredUsers = allUsers;
  if (query.condition && query.condition.field && query.condition.operator) {
    if (query.condition.field === 'status') {
      filteredUsers = allUsers.filter(
        user => user.status === query.condition!.value,
      );
    }
  }

  // Create a readable stream that emits users one by one
  return new ReadableStream({
    start(controller) {
      let index = 0;
      const interval = setInterval(() => {
        if (index < filteredUsers.length) {
          controller.enqueue({ data: filteredUsers[index] });
          index++;
        } else {
          controller.close();
          clearInterval(interval);
        }
      }, 300);
    },
  });
};

// Component that uses useListStreamQuery
interface ListStreamQueryDemoProps {
  filterStatus?: string;
  autoExecute?: boolean;
}

function ListStreamQueryDemo({
  filterStatus = 'all',
  autoExecute = true,
}: ListStreamQueryDemoProps) {
  const [streamedUsers, setStreamedUsers] = React.useState<User[]>([]);
  const [isStreaming, setIsStreaming] = React.useState(false);

  const getInitialQuery = (): ListQuery => {
    return {
      condition: filterStatus === 'all' ? all() : eq('status', filterStatus),
    };
  };

  const { loading, result, error, execute, setQuery } =
    useListStreamQuery<User>({
      initialQuery: getInitialQuery(),
      execute: mockFetchUserStream,
      autoExecute,
    });

  React.useEffect(() => {
    if (result) {
      setIsStreaming(true);
      setStreamedUsers([]);

      const reader = result.getReader();
      const readStream = async () => {
        try {
          while (true) {
            const { done, value } = await reader.read();
            if (done) {
              setIsStreaming(false);
              break;
            }

            setStreamedUsers(prev => [...prev, value.data]);
          }
        } catch (err) {
          console.error('Stream reading error:', err);
          setIsStreaming(false);
        }
      };

      readStream();
    }
  }, [result]);

  const handleFilterChange = (status: string) => {
    setStreamedUsers([]);
    setQuery({
      condition: status === 'all' ? all() : eq('status', status),
    });
  };

  const handleManualExecute = () => {
    setStreamedUsers([]);
    execute();
  };

  return (
    <Card title="useListStreamQuery Demo" style={{ width: 500 }}>
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
            Execute Stream Query
          </Button>
        )}

        {loading && <Text type="warning">Starting stream...</Text>}
        {isStreaming && <Text type="success">Streaming data...</Text>}

        {streamedUsers.length > 0 && (
          <div>
            <Text strong>Streamed Users ({streamedUsers.length}):</Text>
            <List
              size="small"
              dataSource={streamedUsers}
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
          This demonstrates the useListStreamQuery hook for streaming list data
          with conditions. Users are streamed one by one with a delay.
        </Text>
      </Space>
    </Card>
  );
}

const meta: Meta<typeof ListStreamQueryDemo> = {
  title: 'React/Hooks/useListStreamQuery',
  component: ListStreamQueryDemo,
  parameters: {
    layout: 'centered',
    docs: {
      description: {
        component:
          'A React hook for querying streaming list data with conditions, projection, and sorting.',
      },
    },
  },
  tags: ['autodocs'],
  argTypes: {
    filterStatus: {
      control: { type: 'select' },
      options: ['all', 'active', 'inactive'],
      description: 'Status filter for streaming users',
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

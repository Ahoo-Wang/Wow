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
import { useSingleQuery } from '../useSingleQuery';
import { Button, Card, Typography, Space, Alert, Input } from 'antd';

const { Text } = Typography;

interface User {
  id: number;
  name: string;
  email: string;
  status: 'active' | 'inactive';
}

// Mock API function
const mockFetchUser = async (query: any): Promise<User> => {
  // Simulate API delay
  await new Promise(resolve => setTimeout(resolve, 1000));

  // Mock data
  const users: Record<number, User> = {
    1: { id: 1, name: 'John Doe', email: 'john@example.com', status: 'active' },
    2: {
      id: 2,
      name: 'Jane Smith',
      email: 'jane@example.com',
      status: 'active',
    },
    3: {
      id: 3,
      name: 'Bob Johnson',
      email: 'bob@example.com',
      status: 'inactive',
    },
  };

  // Simple ID lookup from condition
  let userId = 1;
  if (query.condition && Array.isArray(query.condition)) {
    const idCondition = query.condition.find((c: any) => c.field === 'id');
    if (idCondition) {
      userId = idCondition.value;
    }
  }

  const user = users[userId];
  if (!user) {
    throw new Error(`User with id ${userId} not found`);
  }

  return user;
};

// Component that uses useSingleQuery
interface SingleQueryDemoProps {
  initialUserId: number;
  autoExecute?: boolean;
}

const SingleQueryDemo: React.FC<SingleQueryDemoProps> = ({
                                                           initialUserId,
                                                           autoExecute = true,
                                                         }) => {
  const [userId, setUserId] = React.useState(initialUserId);

  const { loading, result, error, execute, setQuery } = useSingleQuery<User>({
    initialQuery: {
      condition: [{ field: 'id', operator: 'eq' as any, value: initialUserId }],
    } as any,
    execute: mockFetchUser,
    autoExecute,
  });

  const handleUserIdChange = (newId: number) => {
    setUserId(newId);
    setQuery({
      condition: [{ field: 'id', operator: 'eq' as any, value: newId }],
    } as any);
  };

  const handleManualExecute = () => {
    execute();
  };

  return (
    <Card title="useSingleQuery Demo" style={{ width: 500 }}>
      <Space direction="vertical" style={{ width: '100%' }}>
        <div>
          <Text strong>User ID: </Text>
          <Input
            type="number"
            value={userId}
            onChange={e => setUserId(Number(e.target.value))}
            placeholder="Enter user ID (1, 2, or 3)"
            style={{ width: 150 }}
          />
          <Button
            onClick={() => handleUserIdChange(userId)}
            style={{ marginLeft: 8 }}
          >
            Load User
          </Button>
        </div>

        {!autoExecute && (
          <Button onClick={handleManualExecute} type="primary">
            Execute Single Query
          </Button>
        )}

        {loading && <Text type="warning">Loading user...</Text>}

        {result && (
          <Alert
            message="User Found"
            description={
              <div>
                <p>
                  <strong>ID:</strong> {result.id}
                </p>
                <p>
                  <strong>Name:</strong> {result.name}
                </p>
                <p>
                  <strong>Email:</strong> {result.email}
                </p>
                <p>
                  <strong>Status:</strong>{' '}
                  <span
                    style={{
                      color: result.status === 'active' ? 'green' : 'red',
                    }}
                  >
                    {result.status}
                  </span>
                </p>
              </div>
            }
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
          This demonstrates the useSingleQuery hook for querying a single item
          with conditions.
        </Text>
      </Space>
    </Card>
  );
};

const meta: Meta<typeof SingleQueryDemo> = {
  title: 'React/Hooks/useSingleQuery',
  component: SingleQueryDemo,
  parameters: {
    layout: 'centered',
    docs: {
      description: {
        component:
          'A React hook for querying a single item with conditions, projection, and sorting.',
      },
    },
  },
  tags: ['autodocs'],
  argTypes: {
    initialUserId: {
      control: { type: 'number', min: 1, max: 3 },
      description: 'Initial user ID to load',
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

export const Default: Story = {
  args: {
    initialUserId: 1,
    autoExecute: true,
  },
};

export const ManualExecute: Story = {
  args: {
    initialUserId: 2,
    autoExecute: false,
  },
};

export const WithError: Story = {
  args: {
    initialUserId: 999,
    autoExecute: true,
  },
};

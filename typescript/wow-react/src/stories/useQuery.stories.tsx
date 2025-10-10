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
import { useQuery } from '../useQuery';
import { Button, Card, Typography, Space, Alert, Input } from 'antd';

const { Text } = Typography;

interface User {
  id: string;
  name: string;
  email: string;
}

interface UserQuery {
  id: string;
}

// Mock API function
const mockFetchUser = async (query: UserQuery): Promise<User> => {
  // Simulate API delay
  await new Promise(resolve => setTimeout(resolve, 1000));

  // Mock data
  const users: Record<string, User> = {
    '1': { id: '1', name: 'John Doe', email: 'john@example.com' },
    '2': { id: '2', name: 'Jane Smith', email: 'jane@example.com' },
    '3': { id: '3', name: 'Bob Johnson', email: 'bob@example.com' },
  };

  const user = users[query.id];
  if (!user) {
    throw new Error(`User with id ${query.id} not found`);
  }

  return user;
};

// Component that uses useQuery
interface UserProfileProps {
  initialUserId: string;
  autoExecute?: boolean;
}

const UserProfile: React.FC<UserProfileProps> = ({
                                                   initialUserId,
                                                   autoExecute = true,
                                                 }) => {
  const [userId, setUserId] = React.useState(initialUserId);

  const { loading, result, error, execute, setQuery } = useQuery<
    UserQuery,
    User
  >({
    initialQuery: { id: initialUserId },
    execute: mockFetchUser,
    autoExecute,
  });

  const handleUserIdChange = (newId: string) => {
    setUserId(newId);
    setQuery({ id: newId });
  };

  const handleManualExecute = () => {
    execute();
  };

  return (
    <Card title="useQuery Demo" style={{ width: 400 }}>
      <Space direction="vertical" style={{ width: '100%' }}>
        <div>
          <Text strong>User ID: </Text>
          <Input
            value={userId}
            onChange={e => setUserId(e.target.value)}
            placeholder="Enter user ID (1, 2, or 3)"
            style={{ width: 200 }}
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
            Execute Query
          </Button>
        )}

        {loading && <Text type="warning">Loading user data...</Text>}

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
          This demonstrates the useQuery hook with query parameters and
          auto-execution.
        </Text>
      </Space>
    </Card>
  );
};

const meta: Meta<typeof UserProfile> = {
  title: 'React/Hooks/useQuery',
  component: UserProfile,
  parameters: {
    layout: 'centered',
    docs: {
      description: {
        component:
          'A React hook for managing query-based asynchronous operations with automatic execution.',
      },
    },
  },
  tags: ['autodocs'],
  argTypes: {
    initialUserId: {
      control: 'text',
      description: 'The initial user ID to load',
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

export const AutoExecute: Story = {
  args: {
    initialUserId: '1',
    autoExecute: true,
  },
};

export const ManualExecute: Story = {
  args: {
    initialUserId: '2',
    autoExecute: false,
  },
};

export const WithError: Story = {
  args: {
    initialUserId: '999',
    autoExecute: true,
  },
};

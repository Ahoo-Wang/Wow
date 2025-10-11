import React, { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import {
  Card,
  Typography,
  Space,
  Button,
  Input,
  Row,
  Col,
  Tag,
  Tabs,
  Alert,
  List,
  Descriptions,
} from 'antd';
// Note: We avoid importing the actual classes to prevent Storybook parsing issues with decorators
// import { CommandClient } from '../command/commandClient';
// import { SnapshotQueryClient } from '../query/snapshot/snapshotQueryClient';
// import { EventStreamQueryClient } from '../query/event/eventStreamQueryClient';

const { Title, Text, Paragraph } = Typography;
const { TextArea } = Input;
const { TabPane } = Tabs;

const meta: Meta = {
  title: 'Wow/CQRS Framework',
  parameters: {
    docs: {
      description: {
        component:
          'CQRS/DDD framework integration for Fetcher. Provides command clients, query clients, and real-time event streaming for domain-driven design.',
      },
    },
  },
};

export default meta;
type Story = StoryObj;

const WowDemo: React.FC = () => {
  const [commandResult, setCommandResult] = useState('');
  const [queryResult, setQueryResult] = useState('');
  const [streamResult, setStreamResult] = useState('');
  const [logs, setLogs] = useState<string[]>([]);

  const addLog = (message: string) => {
    setLogs(prev => [
      ...prev,
      `${new Date().toLocaleTimeString()}: ${message}`,
    ]);
  };

  const handleSendCommand = async () => {
    try {
      addLog('Sending command...');
      // Mock command client behavior for demo
      // In real usage: const commandClient = new CommandClient({ basePath: '/api/commands' });
      await new Promise(resolve => setTimeout(resolve, 500)); // Simulate API call
      addLog('Command sent successfully (mocked)');
      setCommandResult('Command executed successfully');
    } catch (error) {
      addLog(`Command failed: ${error}`);
    }
  };

  const handleQuerySnapshot = async () => {
    try {
      addLog('Querying snapshot data...');
      // Mock query client behavior for demo
      // In real usage: const queryClient = new SnapshotQueryClient({ basePath: '/api/queries' });
      await new Promise(resolve => setTimeout(resolve, 800)); // Simulate API call
      addLog('Snapshot query completed (mocked)');
      setQueryResult('Query returned mock data');
    } catch (error) {
      addLog(`Query failed: ${error}`);
    }
  };

  const handleStreamEvents = async () => {
    try {
      addLog('Starting event stream...');
      // Mock event stream client behavior for demo
      // In real usage: const streamClient = new EventStreamQueryClient({ basePath: '/api/streams' });
      await new Promise(resolve => setTimeout(resolve, 300)); // Simulate API call
      addLog('Event stream started (mocked)');
      setStreamResult('Event stream active');
    } catch (error) {
      addLog(`Stream failed: ${error}`);
    }
  };

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card>
        <Title level={3}>🏗️ Wow CQRS/DDD Framework Integration</Title>
        <Paragraph>
          Complete integration with the Wow domain-driven design framework.
          Provides command clients for sending domain commands, query clients
          for retrieving data, and real-time event streaming for reactive
          applications.
        </Paragraph>
      </Card>

      <Alert
        message="🎯 CQRS Architecture"
        description="Command Query Responsibility Segregation separates write operations (commands) from read operations (queries) for better scalability and performance."
        type="info"
        showIcon
        style={{ marginBottom: 24 }}
      />

      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card title="⚡ Command Client" size="small">
            <Space direction="vertical" style={{ width: '100%' }}>
              <Text>Send domain commands to modify application state</Text>
              <Button onClick={handleSendCommand} type="primary" block>
                Send Command
              </Button>
              {commandResult && <Text type="success">{commandResult}</Text>}
            </Space>
          </Card>
        </Col>

        <Col xs={24} md={8}>
          <Card title="🔍 Query Client" size="small">
            <Space direction="vertical" style={{ width: '100%' }}>
              <Text>
                Query snapshot data with rich filtering and pagination
              </Text>
              <Button onClick={handleQuerySnapshot} type="primary" block>
                Query Data
              </Button>
              {queryResult && <Text type="success">{queryResult}</Text>}
            </Space>
          </Card>
        </Col>

        <Col xs={24} md={8}>
          <Card title="📡 Event Streaming" size="small">
            <Space direction="vertical" style={{ width: '100%' }}>
              <Text>Real-time event streaming for reactive applications</Text>
              <Button onClick={handleStreamEvents} type="primary" block>
                Start Stream
              </Button>
              {streamResult && <Text type="success">{streamResult}</Text>}
            </Space>
          </Card>
        </Col>
      </Row>

      <Card title="📋 Query DSL Demo" size="small">
        <Tabs defaultActiveKey="1">
          <TabPane tab="Condition Builder" key="1">
            <Space direction="vertical" style={{ width: '100%' }}>
              <Text>Build complex query conditions with the powerful DSL:</Text>
              <pre
                style={{
                  background: '#f6f8fa',
                  border: '1px solid #d1d9e0',
                  borderRadius: '6px',
                  padding: '16px',
                  overflow: 'auto',
                  fontFamily:
                    'SFMono-Regular, Consolas, Liberation Mono, Menlo, monospace',
                  fontSize: '14px',
                }}
              >
                {`// Example condition building
const condition = Condition.and([
  Condition.eq('status', 'active'),
  Condition.gt('createdAt', '2024-01-01'),
  Condition.or([
    Condition.like('name', 'test%'),
    Condition.in('category', ['A', 'B', 'C'])
  ])
]);

// Use with query client
const results = await queryClient.list('User', {
  condition,
  page: 1,
  size: 20
});`}
              </pre>
            </Space>
          </TabPane>

          <TabPane tab="Available Operators" key="2">
            <List
              size="small"
              dataSource={[
                'EQ - Equal to',
                'NE - Not equal to',
                'GT - Greater than',
                'GTE - Greater than or equal',
                'LT - Less than',
                'LTE - Less than or equal',
                'LIKE - Pattern matching',
                'IN - Value in list',
                'NOT_IN - Value not in list',
                'IS_NULL - Is null',
                'IS_NOT_NULL - Is not null',
                'AND - Logical AND',
                'OR - Logical OR',
                'NOT - Logical NOT',
              ]}
              renderItem={item => <List.Item>{item}</List.Item>}
            />
          </TabPane>
        </Tabs>
      </Card>

      <Card title="Activity Log" size="small">
        <TextArea
          rows={8}
          value={logs.join('\n')}
          readOnly
          placeholder="CQRS operations will appear here..."
        />
      </Card>

      <Card>
        <Title level={4}>Key Features</Title>
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12}>
            <Space direction="vertical">
              <div>
                <Tag color="blue">🔄 CQRS Pattern</Tag>
                <Text> Separate command and query responsibilities</Text>
              </div>
              <div>
                <Tag color="green">🧱 DDD Primitives</Tag>
                <Text> Domain aggregates, events, and value objects</Text>
              </div>
              <div>
                <Tag color="orange">📡 Real-time Streaming</Tag>
                <Text> Server-Sent Events for live data updates</Text>
              </div>
            </Space>
          </Col>
          <Col xs={24} sm={12}>
            <Space direction="vertical">
              <div>
                <Tag color="purple">🔍 Query DSL</Tag>
                <Text> Rich condition building and filtering</Text>
              </div>
              <div>
                <Tag color="red">🚀 Command Client</Tag>
                <Text> Synchronous and streaming command execution</Text>
              </div>
              <div>
                <Tag color="cyan">📊 Multiple Query Types</Tag>
                <Text> Snapshot, event stream, and count queries</Text>
              </div>
            </Space>
          </Col>
        </Row>
      </Card>

      <Card>
        <Title level={4}>Architecture Overview</Title>
        <Descriptions
          bordered
          size="small"
          column={1}
          items={[
            {
              key: 'commands',
              label: 'Commands',
              children:
                'Write operations that modify domain state. Executed via CommandClient with optional streaming responses.',
            },
            {
              key: 'queries',
              label: 'Queries',
              children:
                'Read operations that retrieve data. Support snapshot queries, event streaming, and complex filtering.',
            },
            {
              key: 'events',
              label: 'Events',
              children:
                'Domain events that represent state changes. Can be streamed in real-time for reactive applications.',
            },
            {
              key: 'aggregates',
              label: 'Aggregates',
              children:
                'Domain objects that encapsulate state and behavior. Commands are executed against specific aggregates.',
            },
          ]}
        />
      </Card>
    </Space>
  );
};

export const Default: Story = {
  render: () => <WowDemo />,
};

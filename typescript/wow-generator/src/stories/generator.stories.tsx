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
  Alert,
} from 'antd';

const { Title, Text, Paragraph } = Typography;
const { TextArea } = Input;

const meta: Meta = {
  title: 'Generator/Code Generation',
  parameters: {
    docs: {
      description: {
        component:
          'Code generation utilities for automatically creating API clients, models, and type definitions.',
      },
    },
  },
};

export default meta;
type Story = StoryObj;

const GeneratorDemo: React.FC = () => {
  const [openApiSpec, setOpenApiSpec] = useState(`{
  "openapi": "3.0.0",
  "info": {
    "title": "Sample API",
    "version": "1.0.0"
  },
  "paths": {
    "/users": {
      "get": {
        "summary": "Get users",
        "responses": {
          "200": {
            "description": "Success",
            "content": {
              "application/json": {
                "schema": {
                  "type": "array",
                  "items": {
                    "$ref": "#/components/schemas/User"
                  }
                }
              }
            }
          }
        }
      }
    }
  },
  "components": {
    "schemas": {
      "User": {
        "type": "object",
        "properties": {
          "id": { "type": "integer" },
          "name": { "type": "string" },
          "email": { "type": "string" }
        }
      }
    }
  }
}`);
  const [generatedCode, setGeneratedCode] = useState('');
  const [logs, setLogs] = useState<string[]>([]);

  const addLog = (message: string) => {
    setLogs(prev => [
      ...prev,
      `${new Date().toLocaleTimeString()}: ${message}`,
    ]);
  };

  const handleGenerateClient = () => {
    addLog('Starting API client generation...');
    addLog('Parsing OpenAPI specification...');

    const mockGeneratedCode = `// Generated API Client
import { Fetcher } from '@ahoo-wang/fetcher';

export class UserApiClient {
  constructor(private fetcher: Fetcher) {}

  async getUsers(): Promise<User[]> {
    return this.fetcher.request({
      method: 'GET',
      url: '/users'
    });
  }

  async getUserById(id: number): Promise<User> {
    return this.fetcher.request({
      method: 'GET',
      url: '/users/{id}',
      pathParams: { id }
    });
  }
}

export interface User {
  id: number;
  name: string;
  email: string;
}`;

    setGeneratedCode(mockGeneratedCode);
    addLog('API client generated successfully');
  };

  const handleGenerateModels = () => {
    addLog('Starting model generation...');
    addLog('Extracting schemas from OpenAPI spec...');

    const mockGeneratedCode = `// Generated TypeScript Models
export interface User {
  id: number;
  name: string;
  email: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateUserRequest {
  name: string;
  email: string;
}

export interface UpdateUserRequest {
  name?: string;
  email?: string;
}

export interface ApiResponse<T> {
  data: T;
  message?: string;
  success: boolean;
}`;

    setGeneratedCode(mockGeneratedCode);
    addLog('Models generated successfully');
  };

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card>
        <Title level={3}>⚙️ Code Generation Demo</Title>
        <Paragraph>
          Interactive demonstration of the Fetcher code generation system for
          automatically creating API clients, TypeScript models, and type
          definitions from OpenAPI specifications.
        </Paragraph>
      </Card>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <Card title="OpenAPI Specification" size="small">
            <TextArea
              rows={15}
              value={openApiSpec}
              onChange={e => setOpenApiSpec(e.target.value)}
              placeholder="Paste your OpenAPI JSON specification here..."
            />
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card title="Generated Code" size="small">
            <TextArea
              rows={15}
              value={generatedCode}
              readOnly
              placeholder="Generated code will appear here..."
            />
          </Card>
        </Col>
      </Row>

      <Card title="Generation Actions" size="small">
        <Space wrap>
          <Button onClick={handleGenerateClient} type="primary">
            Generate API Client
          </Button>
          <Button onClick={handleGenerateModels}>Generate Models</Button>
        </Space>
      </Card>

      <Card title="Activity Log" size="small">
        <TextArea
          rows={6}
          value={logs.join('\n')}
          readOnly
          placeholder="Generation activity will appear here..."
        />
      </Card>

      <Card>
        <Title level={4}>Key Features</Title>
        <Space direction="vertical">
          <div>
            <Tag color="blue">OpenAPI Support</Tag>
            <Text> Generates code from OpenAPI 3.0 specifications</Text>
          </div>
          <div>
            <Tag color="green">TypeScript First</Tag>
            <Text>
              {' '}
              Produces fully typed TypeScript code with proper interfaces
            </Text>
          </div>
          <div>
            <Tag color="orange">Multiple Generators</Tag>
            <Text> API clients, data models, and utility functions</Text>
          </div>
          <div>
            <Tag color="purple">Framework Integration</Tag>
            <Text> Built-in support for React hooks and CQRS patterns</Text>
          </div>
        </Space>
      </Card>

      <Alert
        message="Code Generation Workflow"
        description={
          <ol style={{ margin: 0, paddingLeft: '20px' }}>
            <li>Provide OpenAPI specification (JSON format)</li>
            <li>Choose generation target (API client, models, etc.)</li>
            <li>Configure generation options and base URL</li>
            <li>Generate and integrate the code into your project</li>
          </ol>
        }
        type="info"
        showIcon
      />
    </Space>
  );
};

export const Default: Story = {
  render: () => <GeneratorDemo />,
};

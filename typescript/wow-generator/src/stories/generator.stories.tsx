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
  Tabs,
  Progress,
  Tree,
  Select,
  Switch,
  Divider,
  Badge,
  Statistic,
  Timeline,
} from 'antd';
// Icon imports removed - using text-based indicators instead

const { Title, Text, Paragraph } = Typography;
const { TextArea } = Input;
const { TabPane } = Tabs;
const { Option } = Select;
const { DirectoryTree } = Tree;

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

// Sample OpenAPI specifications for different use cases
const SAMPLE_SPECS = {
  basic: {
    name: 'Basic API',
    spec: `{
  "openapi": "3.0.0",
  "info": {
    "title": "Basic API",
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
}`,
  },
  wow: {
    name: 'Wow DDD Framework',
    spec: `{
  "openapi": "3.0.0",
  "info": {
    "title": "Shopping Cart API",
    "version": "1.0.0"
  },
  "paths": {
    "/owner/{ownerId}/cart/view_cart": {
      "put": {
        "operationId": "example.cart.view_cart",
        "summary": "View cart",
        "parameters": [
          {
            "name": "ownerId",
            "in": "path",
            "required": true,
            "schema": { "type": "string" }
          }
        ],
        "requestBody": {
          "content": {
            "application/json": {
              "schema": { "$ref": "#/components/schemas/ViewCart" }
            }
          }
        },
        "responses": {
          "200": { "description": "Success" }
        }
      }
    },
    "/owner/{ownerId}/cart/add_cart_item": {
      "post": {
        "operationId": "example.cart.add_cart_item",
        "summary": "Add item to cart",
        "parameters": [
          {
            "name": "ownerId",
            "in": "path",
            "required": true,
            "schema": { "type": "string" }
          }
        ],
        "requestBody": {
          "content": {
            "application/json": {
              "schema": { "$ref": "#/components/schemas/AddCartItem" }
            }
          }
        },
        "responses": {
          "200": { "description": "Success" }
        }
      }
    }
  },
  "components": {
    "schemas": {
      "ViewCart": {
        "type": "object",
        "properties": {
          "command": {
            "type": "object",
            "properties": {}
          }
        }
      },
      "AddCartItem": {
        "type": "object",
        "properties": {
          "command": {
            "type": "object",
            "properties": {
              "productId": { "type": "string" },
              "quantity": { "type": "integer" }
            }
          }
        }
      },
      "CartState": {
        "type": "object",
        "properties": {
          "items": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "productId": { "type": "string" },
                "quantity": { "type": "integer" },
                "price": { "type": "number" }
              }
            }
          },
          "total": { "type": "number" }
        }
      }
    }
  }
}`,
  },
  complex: {
    name: 'Complex API with Tags',
    spec: `{
  "openapi": "3.0.0",
  "info": {
    "title": "E-commerce API",
    "version": "1.0.0"
  },
  "tags": [
    { "name": "products", "description": "Product management" },
    { "name": "orders", "description": "Order management" }
  ],
  "paths": {
    "/products": {
      "get": {
        "tags": ["products"],
        "summary": "Get products",
        "responses": {
          "200": {
            "description": "Success",
            "content": {
              "application/json": {
                "schema": {
                  "type": "array",
                  "items": { "$ref": "#/components/schemas/Product" }
                }
              }
            }
          }
        }
      }
    },
    "/orders": {
      "post": {
        "tags": ["orders"],
        "summary": "Create order",
        "requestBody": {
          "content": {
            "application/json": {
              "schema": { "$ref": "#/components/schemas/CreateOrderRequest" }
            }
          }
        },
        "responses": {
          "201": {
            "description": "Order created",
            "content": {
              "application/json": {
                "schema": { "$ref": "#/components/schemas/Order" }
              }
            }
          }
        }
      }
    }
  },
  "components": {
    "schemas": {
      "Product": {
        "type": "object",
        "properties": {
          "id": { "type": "string" },
          "name": { "type": "string" },
          "price": { "type": "number" },
          "category": { "$ref": "#/components/schemas/Category" }
        }
      },
      "Category": {
        "type": "object",
        "properties": {
          "id": { "type": "string" },
          "name": { "type": "string" }
        }
      },
      "CreateOrderRequest": {
        "type": "object",
        "properties": {
          "productId": { "type": "string" },
          "quantity": { "type": "integer" },
          "customerEmail": { "type": "string" }
        }
      },
      "Order": {
        "type": "object",
        "properties": {
          "id": { "type": "string" },
          "status": {
            "type": "string",
            "enum": ["pending", "confirmed", "shipped", "delivered"]
          },
          "total": { "type": "number" },
          "createdAt": { "type": "string", "format": "date-time" }
        }
      }
    }
  }
}`,
  },
};

const ComprehensiveGeneratorDemo: React.FC = () => {
  // OpenAPI Spec Editor State
  const [openApiSpec, setOpenApiSpec] = useState(SAMPLE_SPECS.basic.spec);
  const [selectedSample, setSelectedSample] = useState('basic');
  const [specValidation, setSpecValidation] = useState<{
    isValid: boolean;
    errors: string[];
  }>({ isValid: true, errors: [] });

  // Code Generation State
  const [generatedFiles, setGeneratedFiles] = useState<any[]>([]);
  const [selectedFile, setSelectedFile] = useState<string | null>(null);
  const [generationProgress, setGenerationProgress] = useState(0);
  const [isGenerating, setIsGenerating] = useState(false);

  // Configuration State
  const [config, setConfig] = useState({
    targetFramework: 'wow' as 'wow' | 'generic',
    generateModels: true,
    generateClients: true,
    generateAggregates: true,
    outputFormat: 'typescript' as 'typescript' | 'javascript',
    basePath: 'api',
    verbose: false,
  });

  // Activity Log State
  const [logs, setLogs] = useState<string[]>([]);
  const [generationStats, setGenerationStats] = useState({
    filesGenerated: 0,
    modelsCreated: 0,
    clientsCreated: 0,
    timeElapsed: 0,
  });

  const addLog = (
    message: string,
    type: 'info' | 'success' | 'error' | 'warning' = 'info',
  ) => {
    const timestamp = new Date().toLocaleTimeString();
    const logEntry = `[${timestamp}] ${type.toUpperCase()}: ${message}`;
    setLogs(prev => [logEntry, ...prev.slice(0, 49)]); // Keep last 50 logs
  };

  // Validate OpenAPI spec
  const validateSpec = (spec: string) => {
    try {
      const parsed = JSON.parse(spec);
      const errors: string[] = [];

      if (!parsed.openapi) errors.push('Missing openapi version');
      if (!parsed.info) errors.push('Missing info section');
      if (!parsed.paths) errors.push('Missing paths section');

      setSpecValidation({
        isValid: errors.length === 0,
        errors,
      });

      return errors.length === 0;
    } catch {
      setSpecValidation({
        isValid: false,
        errors: ['Invalid JSON format'],
      });
      return false;
    }
  };

  // Handle spec changes
  const handleSpecChange = (value: string) => {
    setOpenApiSpec(value);
    validateSpec(value);
  };

  // Load sample spec
  const handleLoadSample = (sampleKey: string) => {
    const sample = SAMPLE_SPECS[sampleKey as keyof typeof SAMPLE_SPECS];
    setOpenApiSpec(sample.spec);
    setSelectedSample(sampleKey);
    validateSpec(sample.spec);
    addLog(`Loaded sample spec: ${sample.name}`, 'info');
  };

  // Generate mock code based on configuration
  const generateMockCode = () => {
    const startTime = performance.now();
    setIsGenerating(true);
    setGenerationProgress(0);
    addLog('Starting code generation...', 'info');

    // Simulate progress
    const progressInterval = setInterval(() => {
      setGenerationProgress(prev => Math.min(prev + 10, 90));
    }, 200);

    setTimeout(() => {
      clearInterval(progressInterval);
      setGenerationProgress(100);

      const mockFiles = [];

      if (config.generateModels) {
        mockFiles.push({
          name: 'types.ts',
          path: 'src/generated/types.ts',
          content: `// Generated TypeScript Models
export interface User {
  id: number;
  name: string;
  email: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface Product {
  id: string;
  name: string;
  price: number;
  category: Category;
}

export interface Category {
  id: string;
  name: string;
}

export interface Order {
  id: string;
  status: OrderStatus;
  total: number;
  createdAt: string;
}

export enum OrderStatus {
  PENDING = 'pending',
  CONFIRMED = 'confirmed',
  SHIPPED = 'shipped',
  DELIVERED = 'delivered',
}

export interface CreateOrderRequest {
  productId: string;
  quantity: number;
  customerEmail: string;
}`,
          type: 'model',
        });
      }

      if (config.generateClients && config.targetFramework === 'wow') {
        mockFiles.push({
          name: 'commandClient.ts',
          path: 'src/generated/cart/commandClient.ts',
          content: `// Generated WOW Command Client
import { ContentTypeValues } from '@ahoo-wang/fetcher';
import {
  type ApiMetadata,
  type ApiMetadataCapable,
  api,
  attribute,
  autoGeneratedError,
  post,
  put,
  request,
} from '@ahoo-wang/fetcher-decorator';
import { JsonEventStreamResultExtractor } from '@ahoo-wang/fetcher-eventstream';
import type {
  CommandRequest,
  CommandResult,
  CommandResultEventStream,
} from '@ahoo-wang/fetcher-wow';
import {
  AddCartItem,
  ChangeQuantity,
  RemoveCartItem,
  ViewCart,
} from './types';

enum COMMAND_ENDPOINT_PATHS {
  VIEW_CART = '/owner/{ownerId}/cart/view_cart',
  ADD_CART_ITEM = '/owner/{ownerId}/cart/add_cart_item',
  CHANGE_QUANTITY = '/owner/{ownerId}/cart/change_quantity',
  REMOVE_CART_ITEM = '/owner/{ownerId}/cart/remove_cart_item',
}

const DEFAULT_COMMAND_CLIENT_OPTIONS: ApiMetadata = {
  basePath: '${config.basePath}',
};

@api()
export class CartCommandClient implements ApiMetadataCapable {
  constructor(
    public readonly apiMetadata: ApiMetadata = DEFAULT_COMMAND_CLIENT_OPTIONS,
  ) {}

  /** View cart */
  @put(COMMAND_ENDPOINT_PATHS.VIEW_CART)
  viewCart(
    @request() commandRequest: CommandRequest<ViewCart>,
    @attribute() attributes: Record<string, any>,
  ): Promise<CommandResult> {
    throw autoGeneratedError(commandRequest, attributes);
  }

  /** Add item to cart */
  @post(COMMAND_ENDPOINT_PATHS.ADD_CART_ITEM)
  addCartItem(
    @request() commandRequest: CommandRequest<AddCartItem>,
    @attribute() attributes: Record<string, any>,
  ): Promise<CommandResult> {
    throw autoGeneratedError(commandRequest, attributes);
  }

  /** Change quantity */
  @put(COMMAND_ENDPOINT_PATHS.CHANGE_QUANTITY)
  changeQuantity(
    @request() commandRequest: CommandRequest<ChangeQuantity>,
    @attribute() attributes: Record<string, any>,
  ): Promise<CommandResult> {
    throw autoGeneratedError(commandRequest, attributes);
  }

  /** Remove item */
  @put(COMMAND_ENDPOINT_PATHS.REMOVE_CART_ITEM)
  removeCartItem(
    @request() commandRequest: CommandRequest<RemoveCartItem>,
    @attribute() attributes: Record<string, any>,
  ): Promise<CommandResult> {
    throw autoGeneratedError(commandRequest, attributes);
  }
}`,
          type: 'client',
        });

        mockFiles.push({
          name: 'queryClient.ts',
          path: 'src/generated/cart/queryClient.ts',
          content: `// Generated WOW Query Client Factory
import {
  QueryClientFactory,
  QueryClientOptions,
  ResourceAttributionPathSpec,
} from '@ahoo-wang/fetcher-wow';
import {
  CartAggregatedFields,
  CartItemAdded,
  CartItemRemoved,
  CartQuantityChanged,
  CartState,
} from './types';

const DEFAULT_QUERY_CLIENT_OPTIONS: QueryClientOptions = {
  contextAlias: 'example',
  aggregateName: 'cart',
  resourceAttribution: ResourceAttributionPathSpec.OWNER,
};

type DOMAIN_EVENT_TYPES = CartItemAdded | CartItemRemoved | CartQuantityChanged;

export const cartQueryClientFactory = new QueryClientFactory<
  CartState,
  CartAggregatedFields | string,
  DOMAIN_EVENT_TYPES
>(DEFAULT_QUERY_CLIENT_OPTIONS);`,
          type: 'client',
        });
      }

      if (config.generateClients && config.targetFramework === 'generic') {
        mockFiles.push({
          name: 'apiClient.ts',
          path: 'src/generated/apiClient.ts',
          content: `// Generated Generic API Client
import { Fetcher } from '@ahoo-wang/fetcher';

export class ApiClient {
  constructor(private fetcher: Fetcher) {}

  async getUsers(): Promise<User[]> {
    return this.fetcher.get('/users');
  }

  async getUserById(id: number): Promise<User> {
    return this.fetcher.get(\`/users/\${id}\`);
  }

  async createUser(user: CreateUserRequest): Promise<User> {
    return this.fetcher.post('/users', { body: user });
  }

  async getProducts(): Promise<Product[]> {
    return this.fetcher.get('/products');
  }

  async createOrder(order: CreateOrderRequest): Promise<Order> {
    return this.fetcher.post('/orders', { body: order });
  }
}`,
          type: 'client',
        });
      }

      if (config.generateAggregates) {
        mockFiles.push({
          name: 'boundedContext.ts',
          path: 'src/generated/boundedContext.ts',
          content: `// Generated Bounded Context Alias
export const BOUNDED_CONTEXT_ALIAS = '${config.basePath}';`,
          type: 'aggregate',
        });

        mockFiles.push({
          name: 'index.ts',
          path: 'src/generated/index.ts',
          content: `// Generated Index File
export * from './types';
export * from './apiClient';
export * from './boundedContext';

// Re-export WOW clients if applicable
${
  config.targetFramework === 'wow'
    ? `export * from './cart/commandClient';
export * from './cart/queryClient';`
    : ''
}`,
          type: 'index',
        });
      }

      setGeneratedFiles(mockFiles);
      if (mockFiles.length > 0) {
        setSelectedFile(mockFiles[0].path);
      }

      const endTime = performance.now();
      const timeElapsed = endTime - startTime;

      setGenerationStats({
        filesGenerated: mockFiles.length,
        modelsCreated: mockFiles.filter(f => f.type === 'model').length,
        clientsCreated: mockFiles.filter(f => f.type === 'client').length,
        timeElapsed,
      });

      addLog(
        `Code generation completed successfully in ${timeElapsed.toFixed(2)}ms`,
        'success',
      );
      addLog(`Generated ${mockFiles.length} files`, 'info');

      setIsGenerating(false);
    }, 2000);
  };

  // Group files by directory for tree display
  const groupedFiles = generatedFiles.reduce(
    (acc, file) => {
      const dir = file.path.split('/').slice(0, -1).join('/');
      if (!acc[dir]) acc[dir] = [];
      acc[dir].push(file);
      return acc;
    },
    {} as Record<string, any[]>,
  );

  const treeData = Object.keys(groupedFiles).map(dir => ({
    title: dir.split('/').pop(),
    key: dir,
    children: groupedFiles[dir].map((file: any) => ({
      title: file.name,
      key: file.path,
      isLeaf: true,
    })),
  }));

  const selectedFileContent =
    generatedFiles.find(f => f.path === selectedFile)?.content || '';

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card>
        <Title level={2}>⚙️ Comprehensive Code Generator Demo</Title>
        <Paragraph>
          Enterprise-grade TypeScript code generator from OpenAPI
          specifications. Supports Wow DDD framework, generates type-safe
          models, query clients, command clients, and API clients with
          comprehensive configuration options.
        </Paragraph>
      </Card>

      <Tabs defaultActiveKey="spec-editor" size="large">
        <TabPane tab="📝 OpenAPI Spec Editor" key="spec-editor">
          <Row gutter={[16, 16]}>
            <Col xs={24} md={8}>
              <Card title="Sample Specifications" size="small">
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Select
                    value={selectedSample}
                    onChange={handleLoadSample}
                    style={{ width: '100%' }}
                  >
                    {Object.entries(SAMPLE_SPECS).map(([key, sample]) => (
                      <Option key={key} value={key}>
                        {sample.name}
                      </Option>
                    ))}
                  </Select>
                  <Button
                    onClick={() => handleLoadSample(selectedSample)}
                    block
                  >
                    Load Sample
                  </Button>
                </Space>
              </Card>

              <Card
                title="Validation Status"
                size="small"
                style={{ marginTop: 16 }}
              >
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Badge
                    status={specValidation.isValid ? 'success' : 'error'}
                    text={
                      specValidation.isValid
                        ? 'Valid OpenAPI Spec'
                        : 'Invalid Spec'
                    }
                  />
                  {specValidation.errors.length > 0 && (
                    <div>
                      <Text strong style={{ color: 'red' }}>
                        Errors:
                      </Text>
                      {specValidation.errors.map((error, index) => (
                        <div
                          key={index}
                          style={{ color: 'red', fontSize: '12px' }}
                        >
                          • {error}
                        </div>
                      ))}
                    </div>
                  )}
                </Space>
              </Card>
            </Col>

            <Col xs={24} md={16}>
              <Card title="OpenAPI Specification" size="small">
                <TextArea
                  rows={20}
                  value={openApiSpec}
                  onChange={e => handleSpecChange(e.target.value)}
                  placeholder="Paste your OpenAPI JSON specification here..."
                  style={{
                    fontFamily: 'monospace',
                    fontSize: '12px',
                  }}
                />
              </Card>
            </Col>
          </Row>
        </TabPane>

        <TabPane tab="🔧 Configuration" key="configuration">
          <Row gutter={[16, 16]}>
            <Col xs={24} md={12}>
              <Card title="Generation Options" size="small">
                <Space direction="vertical" style={{ width: '100%' }}>
                  <div>
                    <Text strong>Target Framework:</Text>
                    <Select
                      value={config.targetFramework}
                      onChange={value =>
                        setConfig(prev => ({ ...prev, targetFramework: value }))
                      }
                      style={{ width: '100%', marginTop: 8 }}
                    >
                      <Option value="wow">Wow DDD Framework</Option>
                      <Option value="generic">Generic API Client</Option>
                    </Select>
                  </div>

                  <div>
                    <Text strong>Output Format:</Text>
                    <Select
                      value={config.outputFormat}
                      onChange={value =>
                        setConfig(prev => ({ ...prev, outputFormat: value }))
                      }
                      style={{ width: '100%', marginTop: 8 }}
                    >
                      <Option value="typescript">TypeScript</Option>
                      <Option value="javascript">JavaScript</Option>
                    </Select>
                  </div>

                  <div>
                    <Text strong>Base Path:</Text>
                    <Input
                      value={config.basePath}
                      onChange={e =>
                        setConfig(prev => ({
                          ...prev,
                          basePath: e.target.value,
                        }))
                      }
                      style={{ marginTop: 8 }}
                    />
                  </div>
                </Space>
              </Card>
            </Col>

            <Col xs={24} md={12}>
              <Card title="Generation Targets" size="small">
                <Space direction="vertical" style={{ width: '100%' }}>
                  <div>
                    <Switch
                      checked={config.generateModels}
                      onChange={checked =>
                        setConfig(prev => ({
                          ...prev,
                          generateModels: checked,
                        }))
                      }
                    />
                    <Text style={{ marginLeft: 8 }}>Generate Models</Text>
                  </div>

                  <div>
                    <Switch
                      checked={config.generateClients}
                      onChange={checked =>
                        setConfig(prev => ({
                          ...prev,
                          generateClients: checked,
                        }))
                      }
                    />
                    <Text style={{ marginLeft: 8 }}>Generate Clients</Text>
                  </div>

                  <div>
                    <Switch
                      checked={config.generateAggregates}
                      onChange={checked =>
                        setConfig(prev => ({
                          ...prev,
                          generateAggregates: checked,
                        }))
                      }
                    />
                    <Text style={{ marginLeft: 8 }}>Generate Aggregates</Text>
                  </div>

                  <Divider />

                  <div>
                    <Switch
                      checked={config.verbose}
                      onChange={checked =>
                        setConfig(prev => ({ ...prev, verbose: checked }))
                      }
                    />
                    <Text style={{ marginLeft: 8 }}>Verbose Logging</Text>
                  </div>
                </Space>
              </Card>
            </Col>
          </Row>
        </TabPane>

        <TabPane tab="⚡ Code Generation" key="generation">
          <Row gutter={[16, 16]}>
            <Col xs={24} md={8}>
              <Card title="Generation Control" size="small">
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Button
                    onClick={generateMockCode}
                    type="primary"
                    loading={isGenerating}
                    block
                    disabled={!specValidation.isValid}
                  >
                    {isGenerating ? 'Generating...' : 'Generate Code'}
                  </Button>

                  {isGenerating && (
                    <Progress percent={generationProgress} status="active" />
                  )}

                  {!specValidation.isValid && (
                    <Alert
                      message="Invalid OpenAPI Specification"
                      description="Please fix the validation errors before generating code."
                      type="error"
                      showIcon
                    />
                  )}
                </Space>
              </Card>

              <Card
                title="Generation Stats"
                size="small"
                style={{ marginTop: 16 }}
              >
                <Row gutter={[16, 8]}>
                  <Col span={12}>
                    <Statistic
                      title="Files"
                      value={generationStats.filesGenerated}
                    />
                  </Col>
                  <Col span={12}>
                    <Statistic
                      title="Models"
                      value={generationStats.modelsCreated}
                    />
                  </Col>
                  <Col span={12}>
                    <Statistic
                      title="Clients"
                      value={generationStats.clientsCreated}
                    />
                  </Col>
                  <Col span={12}>
                    <Statistic
                      title="Time"
                      value={generationStats.timeElapsed}
                      suffix="ms"
                      precision={0}
                    />
                  </Col>
                </Row>
              </Card>
            </Col>

            <Col xs={24} md={8}>
              <Card title="Generated Files" size="small">
                <DirectoryTree
                  treeData={treeData}
                  selectedKeys={selectedFile ? [selectedFile] : []}
                  onSelect={keys => {
                    if (keys.length > 0) {
                      setSelectedFile(keys[0] as string);
                    }
                  }}
                  style={{ maxHeight: 400, overflow: 'auto' }}
                />
              </Card>
            </Col>

            <Col xs={24} md={8}>
              <Card title="Activity Log" size="small">
                <TextArea
                  rows={15}
                  value={logs.join('\n')}
                  readOnly
                  placeholder="Generation activity will appear here..."
                  style={{ fontFamily: 'monospace', fontSize: '11px' }}
                />
              </Card>
            </Col>
          </Row>
        </TabPane>

        <TabPane tab="📄 Code Preview" key="preview">
          <Row gutter={[16, 16]}>
            <Col xs={24} md={8}>
              <Card title="File Browser" size="small">
                <DirectoryTree
                  treeData={treeData}
                  selectedKeys={selectedFile ? [selectedFile] : []}
                  onSelect={keys => {
                    if (keys.length > 0) {
                      setSelectedFile(keys[0] as string);
                    }
                  }}
                  style={{ maxHeight: 500, overflow: 'auto' }}
                />
              </Card>
            </Col>

            <Col xs={24} md={16}>
              <Card
                title={`Code Preview - ${selectedFile ? selectedFile.split('/').pop() : 'No file selected'}`}
                size="small"
              >
                <TextArea
                  rows={25}
                  value={selectedFileContent}
                  readOnly
                  style={{
                    fontFamily: 'Monaco, Menlo, "Ubuntu Mono", monospace',
                    fontSize: '12px',
                    backgroundColor: '#f6f8fa',
                  }}
                  placeholder="Select a file to preview its generated code..."
                />
              </Card>
            </Col>
          </Row>
        </TabPane>

        <TabPane tab="🔗 Integration Examples" key="integration">
          <Card title="Using Generated Code" size="small">
            <Tabs defaultActiveKey="wow" size="small">
              <TabPane tab="Wow DDD Framework" key="wow">
                <TextArea
                  rows={20}
                  value={`// Using generated Wow DDD clients
import { Fetcher } from '@ahoo-wang/fetcher';
import { cartQueryClientFactory } from './generated/cart/queryClient';
import { CartCommandClient } from './generated/cart/commandClient';

// Create fetcher instance
const fetcher = new Fetcher({
  baseURL: 'https://api.example.com',
});

// Use query client for state queries
const snapshotClient = cartQueryClientFactory.createSnapshotQueryClient({
  fetcher: fetcher
});

const cartState = await snapshotClient.singleState({
  condition: { ownerId: 'user-123' }
});

// Use command client for state changes
const commandClient = new CartCommandClient({ fetcher });

const result = await commandClient.addCartItem({
  command: {
    productId: 'product-456',
    quantity: 2,
  },
}, {
  ownerId: 'user-123',
});`}
                  readOnly
                  style={{ fontFamily: 'monospace', fontSize: '12px' }}
                />
              </TabPane>

              <TabPane tab="Generic API Client" key="generic">
                <TextArea
                  rows={20}
                  value={`// Using generated generic API client
import { Fetcher } from '@ahoo-wang/fetcher';
import { ApiClient } from './generated/apiClient';

// Create fetcher instance
const fetcher = new Fetcher({
  baseURL: 'https://api.example.com',
});

// Create API client instance
const apiClient = new ApiClient(fetcher);

// Use generated methods
const users = await apiClient.getUsers();
const user = await apiClient.getUserById(123);
const products = await apiClient.getProducts();

// Create resources
const newUser = await apiClient.createUser({
  name: 'John Doe',
  email: 'john@example.com',
});

const order = await apiClient.createOrder({
  productId: 'product-456',
  quantity: 2,
  customerEmail: 'john@example.com',
});`}
                  readOnly
                  style={{ fontFamily: 'monospace', fontSize: '12px' }}
                />
              </TabPane>

              <TabPane tab="React Integration" key="react">
                <TextArea
                  rows={20}
                  value={`// React component using generated clients
import React, { useEffect, useState } from 'react';
import { cartQueryClientFactory } from './generated/cart/queryClient';
import { CartCommandClient } from './generated/cart/commandClient';
import { CartState } from './generated/types';

const ShoppingCart: React.FC = () => {
  const [cartState, setCartState] = useState<CartState | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadCart();
  }, []);

  const loadCart = async () => {
    try {
      const snapshotClient = cartQueryClientFactory.createSnapshotQueryClient({
        fetcher: fetcher
      });

      const state = await snapshotClient.singleState({
        condition: { ownerId: 'current-user-id' }
      });

      setCartState(state);
    } catch (error) {
      console.error('Failed to load cart:', error);
    } finally {
      setLoading(false);
    }
  };

  const addItem = async (productId: string, quantity: number) => {
    const commandClient = new CartCommandClient({ fetcher });

    await commandClient.addCartItem({
      command: { productId, quantity },
    }, {
      ownerId: 'current-user-id',
    });

    // Reload cart state
    await loadCart();
  };

  if (loading) return <div>Loading...</div>;

  return (
    <div>
      <h2>Shopping Cart</h2>
      {cartState?.items.map(item => (
        <div key={item.productId}>
          Product: {item.productId}, Quantity: {item.quantity}
        </div>
      ))}
      <button onClick={() => addItem('product-123', 1)}>
        Add Item
      </button>
    </div>
  );
};`}
                  readOnly
                  style={{ fontFamily: 'monospace', fontSize: '12px' }}
                />
              </TabPane>
            </Tabs>
          </Card>
        </TabPane>

        <TabPane tab="🚨 Validation & Errors" key="validation">
          <Row gutter={[16, 16]}>
            <Col xs={24} md={12}>
              <Card title="Common Validation Errors" size="small">
                <Space direction="vertical">
                  <Alert
                    message="Missing OpenAPI Version"
                    description="OpenAPI specification must include 'openapi' field with version 3.0+"
                    type="error"
                    showIcon
                  />
                  <Alert
                    message="Invalid Schema Reference"
                    description="Schema references must point to existing schemas in components/schemas"
                    type="warning"
                    showIcon
                  />
                  <Alert
                    message="Missing Operation ID"
                    description="For Wow framework, operations need operationId following pattern: context.aggregate.action"
                    type="info"
                    showIcon
                  />
                  <Alert
                    message="Unsupported HTTP Method"
                    description="Only GET, POST, PUT, DELETE methods are supported for code generation"
                    type="warning"
                    showIcon
                  />
                </Space>
              </Card>
            </Col>

            <Col xs={24} md={12}>
              <Card title="Error Recovery" size="small">
                <Timeline>
                  <Timeline.Item color="red">
                    <Text strong>Parse Error</Text>
                    <br />
                    <Text>Invalid JSON in OpenAPI spec</Text>
                    <br />
                    <Text type="secondary">Fix: Validate JSON syntax</Text>
                  </Timeline.Item>
                  <Timeline.Item color="orange">
                    <Text strong>Schema Error</Text>
                    <br />
                    <Text>Missing required schema components</Text>
                    <br />
                    <Text type="secondary">
                      Fix: Add missing schemas to components
                    </Text>
                  </Timeline.Item>
                  <Timeline.Item color="blue">
                    <Text strong>Reference Error</Text>
                    <br />
                    <Text>Broken $ref links between schemas</Text>
                    <br />
                    <Text type="secondary">
                      Fix: Ensure all references are valid
                    </Text>
                  </Timeline.Item>
                  <Timeline.Item color="green">
                    <Text strong>Generation Success</Text>
                    <br />
                    <Text>All validations passed</Text>
                    <br />
                    <Text type="secondary">Result: Clean generated code</Text>
                  </Timeline.Item>
                </Timeline>
              </Card>
            </Col>
          </Row>
        </TabPane>
      </Tabs>

      <Card>
        <Title level={4}>🎯 Key Features Demonstrated</Title>
        <Row gutter={[16, 16]}>
          <Col xs={24} md={6}>
            <Space direction="vertical">
              <div>
                <Tag color="blue">📝 OpenAPI 3.0+</Tag>
                <Text> Full spec validation and parsing</Text>
              </div>
              <div>
                <Tag color="green">🏗️ DDD Framework</Tag>
                <Text> Wow domain-driven design support</Text>
              </div>
            </Space>
          </Col>

          <Col xs={24} md={6}>
            <Space direction="vertical">
              <div>
                <Tag color="orange">🔧 TypeScript First</Tag>
                <Text> Fully typed code generation</Text>
              </div>
              <div>
                <Tag color="purple">📦 Multiple Targets</Tag>
                <Text> Models, clients, aggregates</Text>
              </div>
            </Space>
          </Col>

          <Col xs={24} md={6}>
            <Space direction="vertical">
              <div>
                <Tag color="cyan">⚙️ Configurable</Tag>
                <Text> Extensive generation options</Text>
              </div>
              <div>
                <Tag color="magenta">🔍 Validation</Tag>
                <Text> Comprehensive error checking</Text>
              </div>
            </Space>
          </Col>

          <Col xs={24} md={6}>
            <Space direction="vertical">
              <div>
                <Tag color="geekblue">📊 Progress Tracking</Tag>
                <Text> Real-time generation monitoring</Text>
              </div>
              <div>
                <Tag color="gold">🔗 Integration</Tag>
                <Text> Seamless Fetcher ecosystem</Text>
              </div>
            </Space>
          </Col>
        </Row>
      </Card>
    </Space>
  );
};

export const Default: Story = {
  render: () => <ComprehensiveGeneratorDemo />,
};

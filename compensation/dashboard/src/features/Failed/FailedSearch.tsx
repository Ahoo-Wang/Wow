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

import { ClearOutlined, SearchOutlined } from "@ant-design/icons";
import { Button, Col, Form, type FormProps, Input, Row, Space } from "antd";
import { and, type Condition, eq } from "@ahoo-wang/fetcher-wow";
import { useCallback } from "react";

interface FailedSearchProps {
  onSearch?: (condition: Condition) => void;
}

export function FailedSearch({ onSearch }: FailedSearchProps) {
  const [form] = Form.useForm();
  const onFinish: FormProps["onFinish"] = useCallback(
    (values: Record<string, string>) => {
      try {
        const conditions: Condition[] = [];
        Object.keys(values).forEach((key) => {
          const value = values[key];
          if (value) {
            conditions.push(eq(key, value));
          }
        });
        if (onSearch) {
          onSearch(and(...conditions));
        }
      } catch (error) {
        console.error("Search error:", error);
      }
    },
    [onSearch],
  );
  return (
    <Form form={form} layout="vertical" onFinish={onFinish}>
      <Row gutter={24}>
        <Col span={6}>
          <Form.Item name="_id">
            <Input addonBefore="Id" placeholder="Id" />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item name="state.eventId.id">
            <Input addonBefore="EventId" placeholder="EventId" />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item name="state.eventId.aggregateId.aggregateId">
            <Input addonBefore="AggregateId" placeholder="AggregateId" />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item name="state.eventId.aggregateId.contextName">
            <Input
              addonBefore="AggregateContext"
              placeholder="AggregateContext"
            />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item name="state.eventId.aggregateId.aggregateName">
            <Input addonBefore="AggregateName" placeholder="AggregateName" />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item name="state.function.contextName">
            <Input
              addonBefore="ProcessorContext"
              placeholder="ProcessorContext"
            />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item name="state.function.processorName">
            <Input addonBefore="ProcessorName" placeholder="ProcessorName" />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                icon={<SearchOutlined />}
              >
                Search
              </Button>
              <Button htmlType="reset" icon={<ClearOutlined />}>
                Reset
              </Button>
            </Space>
          </Form.Item>
        </Col>
      </Row>
    </Form>
  );
}

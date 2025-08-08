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
import type { FailedCategory } from "./FailedCategory.tsx";
import { Button, Col, Form, Input, Row, Space } from "antd";

interface FailedSearchProps {
  category: FailedCategory;
}

export function FailedSearch({ category }: FailedSearchProps) {
  const onFinish: (values: any) => void = (values) => {
    console.log("Success:", values);
  };
  return (
    <Form layout="vertical" onFinish={onFinish}>
      <Row gutter={24}>
        <Col span={6}>
          <Form.Item name="id" label="Id">
            <Input placeholder="Id" />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item name="eventId" label="EventId">
            <Input placeholder="EventId" />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item name="aggregateId" label="AggregateId">
            <Input placeholder="AggregateId" />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item name="aggregateContext" label="AggregateContext">
            <Input placeholder="AggregateContext" />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item name="aggregateName" label="AggregateName">
            <Input placeholder="AggregateName" />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item name="processorContext" label="ProcessorContext">
            <Input placeholder="ProcessorContext" />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item name="processorName" label="ProcessorName">
            <Input placeholder="ProcessorName" />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item label="Actions">
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
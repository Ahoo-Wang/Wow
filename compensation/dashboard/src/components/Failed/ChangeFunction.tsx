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

import { App, Button, Form, Input, Modal, Space } from "antd";
import type { FunctionInfo } from "@ahoo-wang/fetcher-wow";
import { executionFailedCommandService } from "../../services/executionFailedCommandClient.ts";
import { useState } from "react";

export interface ChangeFunctionProps {
  id: string;
  functionInfo: FunctionInfo;
  onChanged?: () => void;
}

export function ChangeFunction({
  id,
  functionInfo,
  onChanged,
}: ChangeFunctionProps) {
  const { notification } = App.useApp();
  const [form] = Form.useForm<FunctionInfo>();
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);

  const showModal = () => {
    setOpen(true);
    form.setFieldsValue(functionInfo);
  };

  const handleOk = () => {
    form.validateFields().then((values) => {
      setLoading(true);
      executionFailedCommandService
        .changeFunction(id, values)
        .then(() => {
          notification.success({ message: "更改处理函数成功" });
          if (onChanged) {
            onChanged();
          }
          setOpen(false);
        })
        .catch((error) => {
          notification.error({
            message: "更改处理函数失败",
            description: error.message,
          });
        })
        .finally(() => {
          setLoading(false);
        });
    });
  };

  const handleCancel = () => {
    setOpen(false);
  };

  return (
    <>
      <Button onClick={showModal}>更改处理函数</Button>
      <Modal
        title="更改处理函数"
        open={open}
        onOk={handleOk}
        confirmLoading={loading}
        onCancel={handleCancel}
        footer={
          <Space>
            <Button onClick={handleCancel}>取消</Button>
            <Button type="primary" onClick={handleOk} loading={loading}>
              确定
            </Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="contextName"
            label="上下文名称"
            rules={[{ required: true, message: "请输入上下文名称" }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="processorName"
            label="处理器名称"
            rules={[{ required: true, message: "请输入处理器名称" }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="name"
            label="函数名称"
            rules={[{ required: true, message: "请输入函数名称" }]}
          >
            <Input />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}

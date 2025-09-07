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

import { Button, Form, InputNumber, App } from "antd";
import type { RetrySpec } from "../../services";
import { executionFailedCommandService } from "../../services/executionFailedCommandClient.ts";
import { useGlobalDrawer } from "../GlobalDrawer/GlobalDrawer.tsx";

export interface ApplyRetrySpecProps {
  id: string;
  retrySpec: RetrySpec;
}

export function ApplyRetrySpec({ id, retrySpec }: ApplyRetrySpecProps) {
  const [form] = Form.useForm();
  const { notification } = App.useApp();
  const { closeDrawer } = useGlobalDrawer();

  form.setFieldsValue({
    maxRetries: retrySpec.maxRetries,
    minBackoff: retrySpec.minBackoff,
    executionTimeout: retrySpec.executionTimeout,
  });

  const handleOk = () => {
    form.validateFields().then((values) => {
      executionFailedCommandService
        .applyRetrySpec(id, values)
        .then(() => {
          notification.info({ message: "应用重试规范成功" });
          closeDrawer();
        })
        .catch((error) => {
          notification.error({
            message: "应用重试规范失败",
            description: error.message,
          });
        });
    });
  };

  return (
    <Form form={form} layout="vertical" onFinish={handleOk}>
      <Form.Item
        name="maxRetries"
        label="最大重试次数"
        rules={[{ required: true, message: "请输入最大重试次数" }]}
      >
        <InputNumber min={0} style={{ width: "100%" }} />
      </Form.Item>
      <Form.Item
        name="minBackoff"
        label="最小退避时间(毫秒)"
        rules={[{ required: true, message: "请输入最小退避时间" }]}
      >
        <InputNumber min={0} style={{ width: "100%" }} />
      </Form.Item>
      <Form.Item
        name="executionTimeout"
        label="执行超时时间(毫秒)"
        rules={[{ required: true, message: "请输入执行超时时间" }]}
      >
        <InputNumber min={0} style={{ width: "100%" }} />
      </Form.Item>
      <Form.Item>
        <Button type={"primary"} htmlType={"submit"} block>
          Submit
        </Button>
      </Form.Item>
    </Form>
  );
}

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

import { Button, Form, InputNumber, App, Input } from "antd";
import type { RetrySpec } from "../../services";
import { executionFailedCommandService } from "../../services/executionFailedCommandClient.ts";
import { useGlobalDrawer } from "../../components/GlobalDrawer/GlobalDrawer.tsx";
import type { OnChangedCapable } from "./Actions.tsx";

export interface ApplyRetrySpecProps extends OnChangedCapable {
  id: string;
  retrySpec: RetrySpec;
}

export function ApplyRetrySpec({
  id,
  retrySpec,
  onChanged,
}: ApplyRetrySpecProps) {
  const [form] = Form.useForm();
  const { notification } = App.useApp();
  const { closeDrawer } = useGlobalDrawer();

  form.setFieldsValue({
    id: id,
    maxRetries: retrySpec.maxRetries,
    minBackoff: retrySpec.minBackoff,
    executionTimeout: retrySpec.executionTimeout,
  });

  const handleOk = () => {
    form.validateFields().then((values) => {
      executionFailedCommandService
        .applyRetrySpec(id, values)
        .then(() => {
          notification.info({ message: "Apply Retry Spec Successfully" });
          onChanged?.()
          closeDrawer();
        })
        .catch((error) => {
          notification.error({
            message: "Failed to Apply Retry Spec",
            description: error.message,
          });
        });
    });
  };

  return (
    <Form form={form} layout="vertical" onFinish={handleOk} size="middle">
      <Form.Item name="id" label="Id">
        <Input readOnly disabled />
      </Form.Item>
      <Form.Item
        name="maxRetries"
        label="Max Retries"
        rules={[{ required: true, message: "Please enter max retries" }]}
      >
        <InputNumber min={0} style={{ width: "100%" }} />
      </Form.Item>
      <Form.Item
        name="minBackoff"
        label="Min Backoff (milliseconds)"
        rules={[{ required: true, message: "Please enter min backoff" }]}
      >
        <InputNumber min={0} style={{ width: "100%" }} />
      </Form.Item>
      <Form.Item
        name="executionTimeout"
        label="Execution Timeout (milliseconds)"
        rules={[{ required: true, message: "Please enter execution timeout" }]}
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

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

import { App, Button, Form, Input, Select } from "antd";
import { type FunctionInfo, type Identifier } from "@ahoo-wang/fetcher-wow";
import { executionFailedCommandService } from "../../services/executionFailedCommandClient.ts";
import type { OnChangedCapable } from "./Actions.tsx";
import { useGlobalDrawer } from "../GlobalDrawer/GlobalDrawer.tsx";

export interface ChangeFunctionProps extends OnChangedCapable {
  id: string;
  functionInfo: FunctionInfo;
}

export function ChangeFunction({
  id,
  functionInfo,
  onChanged,
}: ChangeFunctionProps) {
  const { notification } = App.useApp();
  const [form] = Form.useForm<FunctionInfo & Identifier>();
  const { closeDrawer } = useGlobalDrawer();
  form.setFieldsValue({
    id: id,
    contextName: functionInfo.contextName,
    processorName: functionInfo.processorName,
    name: functionInfo.name,
    functionKind: functionInfo.functionKind,
  });
  const handleOk = () => {
    form.validateFields().then((values) => {
      executionFailedCommandService
        .changeFunction(id, values)
        .then(() => {
          onChanged?.();
          notification.success({ message: "Change Function Success" });
          closeDrawer();
        })
        .catch((error) => {
          notification.error({
            message: "Change Function Failed",
            description: error.message,
          });
        });
    });
  };
  const functionKindOptions = [
    {
      value: "EVENT",
    },
    {
      value: "STATE_EVENT",
    },
  ];
  return (
    <>
      <Form form={form} layout="vertical" onFinish={handleOk}>
        <Form.Item name="id" label="Id">
          <Input readOnly disabled />
        </Form.Item>
        <Form.Item
          name="contextName"
          label="Context Name"
          rules={[{ required: true, message: "Please enter context name" }]}
        >
          <Input />
        </Form.Item>
        <Form.Item
          name="processorName"
          label="Processor Name"
          rules={[{ required: true, message: "Please enter processor name" }]}
        >
          <Input />
        </Form.Item>
        <Form.Item
          name="name"
          label="Function Name"
          rules={[{ required: true, message: "Please enter function name" }]}
        >
          <Input />
        </Form.Item>
        <Form.Item
          name="functionKind"
          label="Function Kind"
          rules={[{ required: true, message: "Please select function kind" }]}
        >
          <Select options={functionKindOptions}></Select>
        </Form.Item>
        <Form.Item>
          <Button type={"primary"} htmlType={"submit"} block>
            Submit
          </Button>
        </Form.Item>
      </Form>
    </>
  );
}

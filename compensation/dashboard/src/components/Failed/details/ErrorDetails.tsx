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

import type { ErrorDetails } from "../../../services";
import { Card, Typography } from "antd";
import { Editor } from "@monaco-editor/react";
const { Text } = Typography;

export interface ErrorDetailsProps {
  error: ErrorDetails;
}

export function ErrorDetails({ error }: ErrorDetailsProps) {
  const errorTitle = (
    <>
      Error Details :
      <Text code>{error.errorCode}</Text>
    </>
  );
  return (
    <>
      <Card
        title={errorTitle}
        size="small"
        styles={{ body: { padding: "0px" } }}
      >
        <Editor
          height="80vh"
          defaultLanguage="java"
          defaultValue={error.stackTrace}
          theme="vs-dark"
          options={{
            readOnly: true,
            minimap: { enabled: false },
            scrollBeyondLastLine: false,
            automaticLayout: true,
            fontSize: 12,
            lineNumbers: "on",
            folding: true,
            wordWrap: "on",
          }}
        />
      </Card>
    </>
  );
}

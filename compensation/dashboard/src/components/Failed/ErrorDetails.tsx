import type { ErrorDetails } from "../../services";
import { Card, Descriptions, Typography } from "antd";
import type { DescriptionsProps } from "antd";
import { Editor } from "@monaco-editor/react";
const { Text } = Typography;

export interface ErrorDetailsProps {
  error: ErrorDetails;
}

export function ErrorDetails({ error }: ErrorDetailsProps) {
  const errorItems: DescriptionsProps["items"] = [
    {
      key: "errorCode",
      label: "Error Code",
      children: <Text code>{error.errorCode}</Text>,
      span: 1,
    },
    {
      key: "errorMsg",
      label: "Error Message",
      children: <Text>{error.errorMsg}</Text>,
      span: 1,
    },
  ];
  
  return (
    <>
      <Descriptions bordered column={1} items={errorItems} size="small" />
      <Card 
        title="Stack Trace" 
        size="small"
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
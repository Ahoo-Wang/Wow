import type { ErrorDetails } from "../../services";
import { Descriptions, Typography } from "antd";
import type { DescriptionsProps } from "antd";
import { Editor } from "@monaco-editor/react";
const { Title,Text } = Typography;
export interface ErrorDetailsProps {
  error: ErrorDetails;
}

export function ErrorDetails({ error }: ErrorDetailsProps) {
  // 错误信息
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
      <Title level={5} style={{ marginTop: 0, marginBottom: 0 }}>
        Stack Trace
      </Title>
      <Editor
        defaultLanguage="java" // 使用java语言类型更适合展示Java堆栈跟踪
        defaultValue={error.stackTrace}
        options={{
          readOnly: true,
          minimap: { enabled: false },
          scrollBeyondLastLine: false,
          automaticLayout: true,
          fontSize: 12,
          lineNumbers: "on", // 显示行号
          folding: true, // 启用代码折叠
          wordWrap: "on", // 自动换行
          theme: "vs-dark", // 使用深色主题，提高可读性
        }}
      />
    </>
  );
}

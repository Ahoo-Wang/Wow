import { Descriptions, Typography, Tag } from "antd";
import type { DescriptionsProps } from "antd";

import type { ExecutionFailedState } from "../../services";
import { Editor } from "@monaco-editor/react";

function formatIsoDateTime(timeAt: number | undefined): string {
  if (!timeAt) {
    return "-";
  }
  return new Date(timeAt).toLocaleString();
}

const { Title, Text } = Typography;

export interface FailedDetailsProps {
  state: ExecutionFailedState;
}

export function FailedDetails({ state }: FailedDetailsProps) {
  // 基本信息
  const basicItems: DescriptionsProps["items"] = [
    {
      key: "id",
      label: "ID",
      children: <Text code copyable>{state.id}</Text>,
      span: 2,
    },
    {
      key: "eventId",
      label: "Event ID",
      children: <Text code copyable>{state.eventId?.id || "-"}</Text>,
      span: 2,
    },
    {
      key: "status",
      label: "Status",
      children: (
        <>
          {state.status === "FAILED" && <Tag color="error">Failed</Tag>}
          {state.status === "PREPARED" && <Tag color="processing">Prepared</Tag>}
          {state.status === "SUCCEEDED" && <Tag color="success">Succeeded</Tag>}
          {!state.status && "-"}
        </>
      ),
      span: 1,
    },
    {
      key: "executeAt",
      label: "Execute At",
      children: formatIsoDateTime(state.executeAt),
      span: 1,
    },
    {
      key: "recoverable",
      label: "Recoverable",
      children: (
        <>
          {state.recoverable === "RECOVERABLE" && <Tag color="success">Recoverable</Tag>}
          {state.recoverable === "UNRECOVERABLE" && <Tag color="error">Unrecoverable</Tag>}
          {state.recoverable === "UNKNOWN" && <Tag color="warning">Unknown</Tag>}
          {!state.recoverable && "-"}
        </>
      ),
      span: 1,
    },
    {
      key: "isRetryable",
      label: "Is Retryable",
      children: state.isRetryable ? <Tag color="success">Yes</Tag> : <Tag color="error">No</Tag>,
      span: 1,
    },
  ];

  // 函数信息
  const functionItems: DescriptionsProps["items"] = [
    {
      key: "contextName",
      label: "Context Name",
      children: state.function?.contextName || "-",
      span: 1,
    },
    {
      key: "processorName",
      label: "Processor Name",
      children: state.function?.processorName || "-",
      span: 1,
    },
    {
      key: "functionName",
      label: "Function Name",
      children: state.function?.name || "-",
      span: 1,
    },
    {
      key: "functionKind",
      label: "Function Kind",
      children: state.function?.functionKind || "-",
      span: 1,
    },
  ];

  // 错误信息
  const errorItems: DescriptionsProps["items"] = [
    {
      key: "errorCode",
      label: "Error Code",
      children: <Text code>{state.error?.errorCode || "-"}</Text>,
      span: 1,
    },
    {
      key: "errorMsg",
      label: "Error Message",
      children: state.error?.errorMsg || "-",
      span: 1,
    },
  ];

  // 重试信息
  const retryItems: DescriptionsProps["items"] = [
    {
      key: "retries",
      label: "Retries",
      children: state.retryState?.retries?.toString() || "-",
      span: 1,
    },
    {
      key: "nextRetryAt",
      label: "Next Retry At",
      children: formatIsoDateTime(state.retryState?.nextRetryAt),
      span: 1,
    },
    {
      key: "maxRetries",
      label: "Max Retries",
      children: state.retrySpec?.maxRetries?.toString() || "-",
      span: 1,
    },
    {
      key: "minBackoff",
      label: "Min Backoff (ms)",
      children: state.retrySpec?.minBackoff?.toString() || "-",
      span: 1,
    },
    {
      key: "executionTimeout",
      label: "Execution Timeout (ms)",
      children: state.retrySpec?.executionTimeout?.toString() || "-",
      span: 1,
    },
  ];

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "16px" }}>
      <Descriptions
        bordered
        column={2}
        items={basicItems}
        size="small"
      />

      <Descriptions
        bordered
        column={2}
        items={functionItems}
        size="small"
      />

      <Descriptions
        bordered
        column={1}
        items={errorItems}
        size="small"
      />

      <Descriptions
        bordered
        column={2}
        items={retryItems}
        size="small"
      />

      {state.error?.stackTrace && (
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
          <Title level={5} style={{ marginTop: 0, marginBottom: "8px" }}>Stack Trace</Title>
          <div style={{ flex: 1, border: "1px solid #d9d9d9", borderRadius: "4px", overflow: "hidden" }}>
            <Editor 
              height="100%"
              defaultLanguage="plaintext"
              defaultValue={state.error?.stackTrace}
              options={{
                readOnly: true,
                minimap: { enabled: false },
                scrollBeyondLastLine: false,
                automaticLayout: true,
                fontSize: 12,
              }}
            />
          </div>
        </div>
      )}
    </div>
  );
}
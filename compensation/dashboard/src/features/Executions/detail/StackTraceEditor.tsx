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

import { PrismLight as SyntaxHighlighter } from "react-syntax-highlighter";
import java from "react-syntax-highlighter/dist/esm/languages/prism/java";
import vscDarkPlus from "react-syntax-highlighter/dist/esm/styles/prism/vsc-dark-plus";
import { useMemo } from "react";
import { useI18n } from "@/i18n.tsx";

SyntaxHighlighter.registerLanguage("java-stack-trace", java);

interface StackTraceEditorProps {
  searchQuery?: string;
  value: string;
  wrapLongLines?: boolean;
}

export function StackTraceEditor({
  searchQuery = "",
  value,
  wrapLongLines = true,
}: StackTraceEditorProps) {
  const { t } = useI18n();
  const matchingLines = useMemo(() => {
    const query = searchQuery.trim().toLocaleLowerCase();
    if (!query) {
      return new Set<number>();
    }
    return new Set(
      value
        .split("\n")
        .map((line, index) =>
          line.toLocaleLowerCase().includes(query) ? index + 1 : undefined,
        )
        .filter((line): line is number => line !== undefined),
    );
  }, [searchQuery, value]);

  return (
    <div
      role="region"
      aria-label={t("Stack trace content")}
      className="h-full overflow-auto bg-[#1e1e1e]"
      tabIndex={0}
    >
      <SyntaxHighlighter
        language="java-stack-trace"
        style={vscDarkPlus}
        showLineNumbers
        wrapLines
        wrapLongLines={wrapLongLines}
        lineProps={(lineNumber) => ({
          "data-search-match": matchingLines.has(lineNumber)
            ? "true"
            : undefined,
          style: matchingLines.has(lineNumber)
            ? { backgroundColor: "rgba(250, 204, 21, 0.16)" }
            : undefined,
        })}
        customStyle={{
          background: "#1e1e1e",
          fontSize: "0.75rem",
          minHeight: "100%",
          lineHeight: "22px",
          margin: 0,
          overflow: "visible",
          padding: "0.75rem 1rem",
        }}
        codeTagProps={{ className: "font-mono" }}
        lineNumberStyle={{
          color: "#94a3b8",
          minWidth: "2.5em",
          paddingRight: "1em",
          userSelect: "none",
        }}
      >
        {value}
      </SyntaxHighlighter>
    </div>
  );
}

export default StackTraceEditor;

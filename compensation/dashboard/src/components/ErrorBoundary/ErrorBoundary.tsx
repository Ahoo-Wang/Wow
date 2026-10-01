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

import * as React from "react";
import { CircleAlert } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useI18n } from "@/i18n.tsx";

interface ErrorBoundaryProps {
  children: React.ReactNode;
}

interface ErrorBoundaryState {
  hasError: boolean;
  error?: Error;
}

function ErrorFallback({ error, retry }: { error?: Error; retry: () => void }) {
  const { t } = useI18n();

  return (
    // The board's ground, through the engine's token class `fve:bg-canvas`.
    <div role="alert" className="flex min-h-svh items-center justify-center fve:bg-canvas p-6">
      <div className="w-full max-w-md rounded-xl border border-destructive/30 bg-card p-6 text-center text-card-foreground shadow-sm">
        <CircleAlert aria-hidden="true" className="mx-auto size-8 text-destructive" />
        <h2 className="mt-3 text-lg font-semibold">
          {t("Something went wrong.")}
        </h2>
        <p className="mt-1 text-sm text-muted-foreground">
          {t("The dashboard could not render this view. Try again to recover from a temporary problem.")}
        </p>
        <Button type="button" className="mt-5" onClick={retry}>
          {t("Try again")}
        </Button>
        <details className="mt-5 text-left text-xs text-muted-foreground">
          <summary className="cursor-pointer font-medium">{t("Technical details")}</summary>
          <pre className="mt-2 overflow-auto whitespace-pre-wrap rounded-lg bg-muted p-3">
            {error?.toString()}
          </pre>
        </details>
      </div>
    </div>
  );
}

export class ErrorBoundary extends React.Component<
  ErrorBoundaryProps,
  ErrorBoundaryState
> {
  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = { hasError: false, error: undefined };
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    console.error("Error caught by error boundary:", error, errorInfo);
  }

  private retry = () => {
    this.setState({ hasError: false, error: undefined });
  };

  render() {
    if (this.state.hasError) {
      return <ErrorFallback error={this.state.error} retry={this.retry} />;
    }

    return this.props.children;
  }
}

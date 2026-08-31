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

import { ExecutionFailedStatus } from "@/generated";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { useI18n, type Message } from "@/i18n.tsx";

interface StatusBadgeProps {
  className?: string;
  status: ExecutionFailedStatus;
}

export function StatusBadge({ className, status }: StatusBadgeProps) {
  const { t } = useI18n();
  const labels: Record<ExecutionFailedStatus, Message> = {
    [ExecutionFailedStatus.FAILED]: "Failed",
    [ExecutionFailedStatus.PREPARED]: "Prepared",
    [ExecutionFailedStatus.SUCCEEDED]: "Succeeded",
  };

  return (
    <Badge
      variant="outline"
      className={cn(
        "rounded-sm px-2 font-medium",
        status === ExecutionFailedStatus.FAILED &&
          "border-red-300 bg-red-50 text-red-700",
        status === ExecutionFailedStatus.PREPARED &&
          "border-blue-300 bg-blue-50 text-blue-600",
        status === ExecutionFailedStatus.SUCCEEDED &&
          "border-emerald-300 bg-emerald-50 text-emerald-700",
        className,
      )}
    >
      {t(labels[status])}
    </Badge>
  );
}

export function BooleanBadge({ value }: { value: boolean }) {
  const { t } = useI18n();
  return (
    <Badge
      variant="outline"
      className={cn(
        "rounded-sm px-2",
        value
          ? "border-emerald-300 bg-emerald-50 text-emerald-700"
          : "border-slate-300 bg-slate-50 text-slate-600",
      )}
    >
      {value ? t("Yes") : t("No")}
    </Badge>
  );
}

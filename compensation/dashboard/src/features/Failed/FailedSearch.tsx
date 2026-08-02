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

import { all, and, eq, id, type Condition } from "@ahoo-wang/fetcher-wow";
import { Filter, LoaderCircle, Search, X } from "lucide-react";
import { useMemo, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";

interface FailedSearchProps {
  onSearch?: (condition: Condition, hasFilters: boolean) => void;
  loading?: boolean;
}

interface SearchField {
  key: string;
  label: string;
  placeholder: string;
  condition: (value: string) => Condition;
}

const searchFields: SearchField[] = [
  {
    key: "_id",
    label: "Execution ID",
    placeholder: "Search by ID…",
    condition: id,
  },
  {
    key: "state.eventId.id",
    label: "Event ID",
    placeholder: "Filter by event ID…",
    condition: (value) => eq("state.eventId.id", value),
  },
  {
    key: "state.eventId.aggregateId.aggregateId",
    label: "Aggregate ID",
    placeholder: "Filter by aggregate ID…",
    condition: (value) => eq("state.eventId.aggregateId.aggregateId", value),
  },
  {
    key: "state.eventId.aggregateId.contextName",
    label: "Aggregate context",
    placeholder: "Filter by aggregate context…",
    condition: (value) => eq("state.eventId.aggregateId.contextName", value),
  },
  {
    key: "state.eventId.aggregateId.aggregateName",
    label: "Aggregate name",
    placeholder: "Filter by aggregate name…",
    condition: (value) => eq("state.eventId.aggregateId.aggregateName", value),
  },
  {
    key: "state.function.contextName",
    label: "Processor context",
    placeholder: "Filter by processor context…",
    condition: (value) => eq("state.function.contextName", value),
  },
  {
    key: "state.function.processorName",
    label: "Processor name",
    placeholder: "Filter by processor name…",
    condition: (value) => eq("state.function.processorName", value),
  },
];

export function FailedSearch({ onSearch, loading }: FailedSearchProps) {
  const [activeKeys, setActiveKeys] = useState<string[]>(["_id"]);
  const [values, setValues] = useState<Record<string, string>>({});

  const activeFields = useMemo(
    () => searchFields.filter((field) => activeKeys.includes(field.key)),
    [activeKeys],
  );
  const appliedFilterCount = activeFields.filter((field) =>
    Boolean(values[field.key]?.trim()),
  ).length;

  const submit = (event?: FormEvent) => {
    event?.preventDefault();
    const conditions = activeFields
      .map((field) => {
        const value = values[field.key]?.trim();
        return value ? field.condition(value) : undefined;
      })
      .filter((condition): condition is Condition => Boolean(condition));
    onSearch?.(
      conditions.length ? and(...conditions) : all(),
      conditions.length > 0,
    );
  };

  const clearAll = () => {
    setActiveKeys(["_id"]);
    setValues({});
    onSearch?.(all(), false);
  };

  const setFieldActive = (key: string, checked: boolean) => {
    if (checked) {
      setActiveKeys((current) => [...new Set([...current, key])]);
      return;
    }
    setActiveKeys((current) =>
      current.filter((activeKey) => activeKey !== key),
    );
    setValues((current) => ({ ...current, [key]: "" }));
  };

  return (
    <form
      className="border-b bg-white px-5 py-[22px]"
      aria-label="Search executions"
      aria-busy={loading}
      onSubmit={submit}
    >
      <div className="flex items-center gap-2">
        <div className="relative min-w-0 flex-1">
          <Input
            aria-label="Execution ID"
            className="h-11 pr-11 text-[15px]"
            placeholder="Search by ID…"
            value={values._id ?? ""}
            onChange={(event) =>
              setValues((current) => ({ ...current, _id: event.target.value }))
            }
          />
          <Button
            type="submit"
            variant="ghost"
            size="icon"
            className="absolute top-0 right-0 size-11"
            aria-label="Search"
            disabled={loading}
          >
            {loading ? (
              <LoaderCircle className="animate-spin motion-reduce:animate-none" />
            ) : (
              <Search />
            )}
          </Button>
        </div>
        <Popover>
          <PopoverTrigger asChild>
            <Button
              type="button"
              variant="outline"
              className="h-11 px-4"
              aria-label="Add filter"
            >
              <Filter />
              {appliedFilterCount > 0
                ? `Filters (${appliedFilterCount})`
                : "Add filter"}
            </Button>
          </PopoverTrigger>
          <PopoverContent align="end" className="w-64 p-2">
            <div className="px-2 pb-2 text-xs font-medium text-muted-foreground">
              Search fields
            </div>
            {searchFields.slice(1).map((field) => {
              const checked = activeKeys.includes(field.key);
              return (
                <Label
                  key={field.key}
                  className="flex cursor-pointer items-center gap-3 rounded-md px-2 py-2 font-normal hover:bg-muted"
                >
                  <Checkbox
                    checked={checked}
                    onCheckedChange={(value) =>
                      setFieldActive(field.key, value === true)
                    }
                  />
                  {field.label}
                </Label>
              );
            })}
          </PopoverContent>
        </Popover>
      </div>

      {activeFields.length > 1 ? (
        <div className="mt-3 grid grid-cols-[repeat(auto-fit,minmax(min(100%,260px),1fr))] gap-2">
          {activeFields.slice(1).map((field) => (
            <div key={field.key} className="flex items-center gap-2">
              <Label className="w-28 shrink-0 text-xs text-muted-foreground">
                {field.label}
              </Label>
              <Input
                aria-label={field.label}
                className="h-9"
                placeholder={field.placeholder}
                value={values[field.key] ?? ""}
                onChange={(event) =>
                  setValues((current) => ({
                    ...current,
                    [field.key]: event.target.value,
                  }))
                }
              />
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label={`Remove ${field.label}`}
                onClick={() => setFieldActive(field.key, false)}
              >
                <X />
              </Button>
            </div>
          ))}
        </div>
      ) : null}
      <div className="mt-2 flex min-h-5 items-center justify-between gap-3 text-xs text-slate-500">
        <span>Exact match across all fields</span>
        {appliedFilterCount > 0 ? (
          <Button
            type="button"
            variant="link"
            className="h-auto px-0 text-xs"
            aria-label="Clear all filters"
            onClick={clearAll}
          >
            Clear all
          </Button>
        ) : null}
      </div>
    </form>
  );
}

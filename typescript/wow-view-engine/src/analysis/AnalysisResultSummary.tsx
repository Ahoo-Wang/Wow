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
import type {
  AnalysisSession,
  ViewDefinition,
} from '../contracts/viewModel.js';
import type { FilterCompilerRegistry } from '../filter/filterModel.js';
import type { DeepReadonly } from '../lib/types.js';
import { describeConfiguredFilter } from '../filter/describeConfiguredFilter.js';
import { SortDirection } from '@ahoo-wang/fetcher-wow';

/** All labels and values describe the successful execution, never the working query. */
export function AnalysisResultSummary({
  result,
  definition,
  compilers,
}: {
  result: NonNullable<AnalysisSession['result']>;
  definition: DeepReadonly<ViewDefinition>;
  compilers: FilterCompilerRegistry;
}) {
  const { config, plan, rows, receivedAt } = result;
  const scope = definition.analysis?.scopes?.find(
    scope => scope.id === config.scope?.id,
  );
  const filter =
    describeConfiguredFilter(
      config.filters.root,
      definition.fields,
      definition.allowedOperators,
      compilers,
      plan.timeZone,
    )?.text ?? '全部记录';
  return (
    <section
      aria-label="执行口径"
      className="fve:flex fve:flex-col fve:gap-2 fve:rounded-md fve:bg-muted/50 fve:p-3 fve:text-xs fve:text-muted-foreground"
    >
      <p>筛选：{filter}</p>
      <p>
        分组：{config.dimensions.map(item => item.title).join('、') || '不分组'}{' '}
        · 指标：
        {config.metrics
          .map(item => {
            const aggregation = plan.schema.find(
              column => column.alias === item.alias,
            )?.aggregation;
            const labels: Record<string, string> = {
              SUM: '求和',
              AVG: '平均值',
              MIN: '最小值',
              MAX: '最大值',
              COUNT: '计数',
              ANY: '代表值',
            };
            return `${item.title}${aggregation ? `（${labels[aggregation] ?? aggregation}）` : ''}`;
          })
          .join('、')}
      </p>
      <p>
        本地接收：{new Date(receivedAt).toLocaleString('zh-CN')} · 已返回{' '}
        {rows.length} 行
        {plan.query.limit !== undefined
          ? ` · 返回上限 ${plan.query.limit} 行`
          : ''}
      </p>
      {plan.query.limit !== undefined && rows.length >= plan.query.limit && (
        <p>达到返回上限，可能仍有其他分组。</p>
      )}
      <details>
        <summary className="fve:cursor-pointer">查看本次口径</summary>
        <p>
          统计对象：{scope?.label ?? config.scope?.id ?? '根记录'} · 时区：
          {plan.timeZone ?? 'UTC'}
        </p>
        {plan.query.elements?.map((element, index) => (
          <p key={index}>
            元素 {index + 1}：
            {config.scope?.filters[index]
              ? (describeConfiguredFilter(
                  config.scope.filters[index].root,
                  scope?.elements[index]?.fields ?? [],
                  definition.allowedOperators,
                  compilers,
                  plan.timeZone,
                )?.text ?? '全部记录')
              : element.path}
          </p>
        ))}
        <p>
          服务端排序：
          {plan.query.sort
            ?.map(
              item =>
                `${plan.schema.find(column => column.alias === item.field)?.title ?? item.field} ${item.direction === SortDirection.DESC ? '降序' : '升序'}`,
            )
            .join('、') || '未指定'}
        </p>
      </details>
      <p>数据表用于核对本次聚合返回结果，不代表业务全量数据。</p>
    </section>
  );
}

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

import { OperatorLocale } from './operatorLocale';

export const zh_CN: OperatorLocale = {
  AND: '与',
  OR: '或',
  NOR: '非或',
  ID: 'ID 等于',
  IDS: 'ID 包含',
  AGGREGATE_ID: '聚合 ID 等于',
  AGGREGATE_IDS: '聚合 ID 包含',
  TENANT_ID: '租户 ID 等于',
  OWNER_ID: '所有者 ID 等于',
  DELETED: '已删除',
  ALL: '全部',
  EQ: '等于',
  NE: '不等于',
  GT: '大于',
  LT: '小于',
  GTE: '大于等于',
  LTE: '小于等于',
  CONTAINS: '包含',
  IN: '包含',
  NOT_IN: '不包含',
  BETWEEN: '在范围内',
  ALL_IN: '全部包含',
  STARTS_WITH: '以...开头',
  ENDS_WITH: '以...结尾',
  ELEM_MATCH: '元素匹配',
  NULL: '为空',
  NOT_NULL: '不为空',
  TRUE: '为真',
  FALSE: '为假',
  EXISTS: '存在',
  TODAY: '今天',
  BEFORE_TODAY: '今天之前',
  TOMORROW: '明天',
  THIS_WEEK: '本周',
  NEXT_WEEK: '下周',
  LAST_WEEK: '上周',
  THIS_MONTH: '本月',
  LAST_MONTH: '上月',
  RECENT_DAYS: '最近几天',
  EARLIER_DAYS: '几天前',
  RAW: '原始查询',
};

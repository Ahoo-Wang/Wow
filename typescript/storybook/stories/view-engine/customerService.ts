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

import type { RecordData } from '@ahoo-wang/wow-view-engine';
import { installRecordedWowService } from './recordedWowService.js';

/**
 * The host the customer scenes' regression stories point at. No network
 * answers it: `installRecordedCustomerService` and
 * `installRecordedCustomerEventService` do, in the page.
 */
export const RECORDED_CRM_HOST = 'https://crm.example.test';

/** 2026-09-18 16:00 in Shanghai: every time below is hours after it. */
const START = Date.parse('2026-09-18T08:00:00.000Z');
const at = (hours: number) => START + hours * 3_600_000;

interface Contact {
  name: string;
  role: string;
  isPrimary?: boolean;
  accountStatus?: string;
}

interface Customer {
  id: string;
  name: string;
  tenantId: string;
  ownerId: string | null;
  industry: string | null;
  status?: string;
  contacts?: Contact[];
  /** Hours after `START` it was created, and last changed. */
  created: number;
  changed?: number;
  version?: number;
}

/**
 * Customers shaped like the snapshots the CRM service returns for
 * `customer`, captured from the dev service and anonymised: the names, the
 * contacts and their numbers are made up, the shape and the spread are the
 * service's — most customers in the public pool (no owner), a few owned,
 * contacts only on some, several tenants. Created two a day over three days,
 * so the daily count has three days to count.
 */
const CUSTOMERS: Customer[] = [
  {
    id: 'CUS-1',
    name: '华东精密仪器',
    tenantId: 'tenant-a',
    ownerId: 'sales-a',
    industry: '工业传感器',
    contacts: [
      {
        name: '联系人甲',
        role: '决策者',
        isPrimary: true,
        accountStatus: 'OPENED',
      },
    ],
    created: 1,
    changed: 60,
    version: 4,
  },
  {
    id: 'CUS-2',
    name: '北辰智造',
    tenantId: 'tenant-a',
    ownerId: null,
    industry: '智能制造',
    contacts: [
      { name: '联系人乙', role: '决策者', isPrimary: true },
      { name: '联系人丙', role: '影响者' },
    ],
    created: 2,
    changed: 3,
    version: 2,
  },
  {
    id: 'CUS-3',
    name: '远航设备集成',
    tenantId: 'tenant-b',
    ownerId: 'sales-b',
    industry: '设备集成',
    contacts: [{ name: '联系人丁', role: '影响者', isPrimary: true }],
    created: 25,
  },
  {
    id: 'CUS-4',
    name: '星河传感',
    tenantId: 'tenant-a',
    ownerId: null,
    industry: null,
    created: 26,
  },
  {
    id: 'CUS-5',
    name: '青禾制造',
    tenantId: 'tenant-b',
    ownerId: 'sales-a',
    industry: '智能制造',
    status: 'DISABLED',
    created: 49,
    changed: 55,
    version: 3,
  },
  {
    id: 'CUS-6',
    name: '云帆集成',
    tenantId: 'tenant-a',
    ownerId: null,
    industry: '设备集成',
    created: 50,
  },
];

let contactSeq = 0;

function snapshot(customer: Customer): RecordData {
  const {
    id,
    name,
    tenantId,
    ownerId,
    industry,
    status = 'ENABLED',
    contacts = [],
    created,
    changed = created,
    version = 1,
  } = customer;
  return {
    contextName: 'crm-service',
    aggregateName: 'customer',
    tenantId,
    ownerId: '',
    spaceId: '',
    version,
    eventId: `${id}-v${version}`,
    firstOperator: '(0)',
    operator: '(0)',
    firstEventTime: at(created),
    eventTime: at(changed),
    snapshotTime: at(changed),
    deleted: false,
    aggregateId: id,
    tags: {},
    state: {
      id,
      name,
      status,
      ownerId,
      basicInfo: {
        bizId: null,
        alias: null,
        industry,
        staffNum: industry ? 120 : 0,
        annualRevenue: industry ? 5_000_000 : null,
        description: '',
      },
      contacts: contacts.map(contact => ({
        id: `CT-${++contactSeq}`,
        title: null,
        phone: null,
        mobile: `1380000${String(contactSeq).padStart(4, '0')}`,
        email: null,
        wechat: null,
        isPrimary: false,
        gender: null,
        remark: null,
        memberId: null,
        accountStatus: 'NONE',
        openingId: null,
        failureCode: null,
        ...contact,
      })),
      deliveryAddresses: [],
      invoices: [],
      enterpriseId: { id: `ENT-${id}`, name: `${name}企业` },
      extend: {},
      financialOnboardingRequested: false,
      salesInfo: {
        source: '',
        saleChannel: '',
        level: 'B',
        customerType: '',
        lifecycleStage: '',
        status: '',
        creditRating: '',
        departmentId: null,
        industryTags: [],
      },
      tags: [],
      timeline: {
        validityStartTime: null,
        validityEndTime: null,
        lastContactTime: null,
        lastActivityTime: null,
        nextFollowUpTime: null,
        contactFrequency: 0,
      },
    },
  };
}

export const RECORDED_CUSTOMERS: RecordData[] = CUSTOMERS.map(snapshot);

const API = 'com.linyikj.crm.api.customer';

/** Wow names an event by its type, in snake case. */
function snake(type: string): string {
  return type.replace(/(?<!^)([A-Z])/g, '_$1').toLowerCase();
}

/**
 * One stream: what one command appended to one customer. `command` is the
 * command's name as the header carries it.
 */
function stream(
  aggregateId: string,
  version: number,
  type: string,
  hours: number,
  command: string,
  body: Record<string, unknown> = {},
): RecordData {
  const id = `${aggregateId}-v${version}`;
  return {
    id,
    contextName: 'crm-service',
    aggregateName: 'customer',
    header: {
      command_operator: 'op-1',
      upstream_name: command,
      trace_id: `${id}-trace`,
    },
    aggregateId,
    tenantId: 'tenant-a',
    ownerId: '',
    spaceId: '',
    commandId: `${id}-command`,
    requestId: `${id}-request`,
    version,
    body: [
      {
        id: `${id}-event`,
        name: snake(type),
        revision: '0.0.1',
        bodyType: `${API}.${type}`,
        body,
      },
    ],
    createTime: at(hours),
  };
}

/**
 * The histories of four of the customers above: one created, given a
 * contact, claimed from the pool and edited; one released back to the
 * pool; one only created; one transferred and then disabled.
 */
export const RECORDED_CUSTOMER_EVENTS: RecordData[] = [
  stream('CUS-1', 1, 'CustomerCreated', 1, 'create_customer', {
    name: '华东精密仪器',
  }),
  stream('CUS-1', 2, 'ContactAdded', 1.5, 'add_contact', {
    contacts: [{ name: '联系人甲', role: '决策者' }],
  }),
  stream('CUS-1', 3, 'CustomerClaimed', 10, 'claim_customer', {
    ownerId: 'sales-a',
  }),
  stream('CUS-1', 4, 'CustomerBasicInfoUpdated', 60, 'update_basic_info', {
    basicInfo: { industry: '工业传感器' },
  }),
  stream('CUS-2', 1, 'CustomerCreated', 2, 'create_customer', {
    name: '北辰智造',
  }),
  stream('CUS-2', 2, 'CustomerReleased', 3, 'release_customer'),
  stream('CUS-3', 1, 'CustomerCreated', 25, 'create_customer', {
    name: '远航设备集成',
  }),
  stream('CUS-5', 1, 'CustomerCreated', 49, 'create_customer', {
    name: '青禾制造',
  }),
  stream('CUS-5', 2, 'CustomerTransferred', 50, 'transfer_customer', {
    ownerId: 'sales-a',
  }),
  stream('CUS-5', 3, 'CustomerDisabled', 55, 'disable_customer'),
];

/**
 * Answers the snapshot queries the customer console sends to the recorded
 * host. The console sends no command, so there is none to answer.
 *
 * Returns the uninstaller, as `beforeEach` expects.
 */
export function installRecordedCustomerService(): () => void {
  return installRecordedWowService({
    host: RECORDED_CRM_HOST,
    resource: 'customer/snapshot',
    documents: RECORDED_CUSTOMERS,
  });
}

/**
 * Answers the event stream queries the customer event console sends to the
 * recorded host. The stream is read-only.
 *
 * Returns the uninstaller, as `beforeEach` expects.
 */
export function installRecordedCustomerEventService(): () => void {
  return installRecordedWowService({
    host: RECORDED_CRM_HOST,
    resource: 'customer/event',
    documents: RECORDED_CUSTOMER_EVENTS,
  });
}

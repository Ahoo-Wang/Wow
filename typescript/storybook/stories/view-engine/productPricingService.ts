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

import type { RecordData } from '@ahoo-wang/fetcher-view-engine';
import { PRICING_EVENT } from './productPricingEvents.js';
import { installRecordedWowService } from './recordedWowService.js';

/**
 * The hosts the product pricing consoles' regression stories point at. No
 * network answers them: the installers below do, in the page.
 */
export const RECORDED_PRICING_HOST = 'https://pricing.example.test';
export const RECORDED_PRICING_EVENTS_HOST =
  'https://pricing-events.example.test';

/** The products the recorded pricings price, as the service holds them. */
const SKU = {
  'SK-1000J': {
    id: 'SK-1000J',
    brandId: '6G7',
    code: 'LA423PF-10R',
    isComposite: false,
    brandName: '上海天逸电器',
    bizId: '626440',
    orderNo: '',
    searchCode: 'LA423PF10R||上海天逸电器',
  },
  'SK-1000T': {
    id: 'SK-1000T',
    brandId: '6G7',
    code: 'LA423PSDF-01/AC220V S',
    isComposite: false,
    brandName: '上海天逸电器',
    bizId: '626455',
    orderNo: '',
    searchCode: 'LA423PSDF01AC220VS||上海天逸电器',
  },
  'SK-QSH6': {
    id: 'SK-QSH6',
    brandId: '6BR',
    code: 'IGYX 12N17B3/L',
    isComposite: false,
    brandName: 'Baumer 堡盟',
    bizId: '2266',
    orderNo: '',
    searchCode: 'IGYX12N17B3L||Baumer 堡盟',
  },
  'SK-1JDF0': {
    id: 'SK-1JDF0',
    brandId: '6EF',
    code: 'EA9AN3C32A',
    isComposite: false,
    brandName: 'Schneider 施耐德',
    bizId: '1553623',
    orderNo: '',
    searchCode: 'EA9AN3C32A||Schneider 施耐德',
  },
  'SK-10001': {
    id: 'SK-10001',
    brandId: '6G7',
    code: 'LA423PDF-10/DC6V Y',
    isComposite: false,
    brandName: '上海天逸电器',
    bizId: '',
    orderNo: '',
    searchCode: 'LA423PDF10DC6VY||上海天逸电器',
  },
} as const;

type SkuId = keyof typeof SKU;

const DAY_MS = 86_400_000;

/**
 * The last millisecond of the day `days` from today, which is how the
 * service writes a deadline. The views compare deadlines against today —
 * in force, lapsing within six months — so the recorded deadlines are kept
 * as distances from the day the twin runs, and answer the same on any day.
 */
function deadlineIn(days: number): number {
  const end = new Date();
  end.setHours(23, 59, 59, 999);
  return end.getTime() + days * DAY_MS;
}

/**
 * Pricings as the service returned them for `product_pricing`, a slice of
 * its own records: one forward product's four quantity tiers, one proxy
 * spot product's three, and one pricing each of three more products. The
 * live service holds only prices in force, so the last two were moved to
 * the other statuses — one stopped, one lapsed — for the status views to
 * have something to show.
 */
export const RECORDED_PRICINGS: RecordData[] = [
  pricing('PP-SK-1000J-40812-1000', 'SK-1000J', 1000, 3000, {
    eventTime: 1789611587493,
  }),
  pricing('PP-SK-1000J-40812-100', 'SK-1000J', 100, 3400, {
    eventTime: 1789611583162,
  }),
  pricing('PP-SK-1000J-40812-10', 'SK-1000J', 10, 3800, {
    eventTime: 1789611578716,
  }),
  pricing('PP-SK-1000J-40812-1', 'SK-1000J', 1, 4200, {
    eventTime: 1789611574587,
  }),
  pricing('PP-SK-1000T-20000-500', 'SK-1000T', 500, 79.8, {
    eventTime: 1789611514623,
  }),
  pricing('PP-SK-1000T-20000-50', 'SK-1000T', 50, 99.8, {
    eventTime: 1789611510383,
  }),
  pricing('PP-SK-1000T-20000-1', 'SK-1000T', 1, 148.8, {
    eventTime: 1789611506397,
  }),
  pricing('PP-SK-QSH6-20000-10', 'SK-QSH6', 10, 123.75, {
    firstEventTime: 1779981962596,
    eventTime: 1785754325607,
    version: 4,
    deadline: deadlineIn(60),
  }),
  pricing('PP-SK-1JDF0-20000-1', 'SK-1JDF0', 1, 110, {
    eventTime: 1779981963000,
    status: 'INACTIVE',
    deadline: deadlineIn(250),
  }),
  pricing('PP-SK-10001-10000-1', 'SK-10001', 1, 120, {
    firstEventTime: 1784010624756,
    eventTime: 1784016897110,
    version: 9,
    status: 'EXPIRED',
    deadline: deadlineIn(-10),
  }),
];

/** A pricing's delivery, which its id spells: forward 8–12 weeks, proxy spot or spot. */
function deliveryOf(id: string) {
  if (id.includes('-40812-'))
    return { type: 'FORWARD', deliveryCycle: { start: 8, end: 12 } };
  if (id.includes('-20000-'))
    return { type: 'PROXY_SPOT', deliveryCycle: { start: 0, end: 0 } };
  return { type: 'SPOT', deliveryCycle: { start: 0, end: 0 } };
}

function pricing(
  id: string,
  sku: SkuId,
  minOrderQuantity: number,
  price: number,
  {
    eventTime,
    firstEventTime = eventTime,
    version = 1,
    status = 'ACTIVE',
    deadline = deadlineIn(460),
  }: {
    eventTime: number;
    firstEventTime?: number;
    version?: number;
    status?: string;
    deadline?: number;
  },
): RecordData {
  return {
    contextName: 'pricing-service',
    aggregateName: 'product_pricing',
    tenantId: 'mydao',
    ownerId: '',
    spaceId: '',
    version,
    firstOperator: '(0)',
    operator: '(0)',
    firstEventTime,
    eventTime,
    state: {
      id,
      skuId: SKU[sku],
      deliveryTime: deliveryOf(id),
      minOrderQuantity,
      price,
      status,
      deadline,
      productCostId: null,
      productCostVersion: null,
    },
    snapshotTime: eventTime,
    tags: {},
    deleted: false,
    aggregateId: id,
  };
}

type EventKind = keyof typeof PRICING_EVENT;

/** Wow names an event by its type, in snake case. */
const EVENT_NAME: Record<EventKind, string> = {
  saved: 'product_pricing_saved',
  statusChanged: 'product_pricing_status_changed',
  tagsApplied: 'default_resource_tags_applied',
};

/** What a saved pricing says: the product, its delivery, quantity and price. */
function saved(
  id: string,
  sku: SkuId,
  minOrderQuantity: number,
  price: number,
  deadline: number,
) {
  return {
    skuId: SKU[sku],
    deliveryTime: deliveryOf(id),
    minOrderQuantity,
    price,
    deadline,
  };
}

const TAGS = { tags: { 'mock.pricing.namespace': ['default'] } };
const ACTIVE = { status: 'ACTIVE' };

/**
 * The event streams of five pricings, as the service returned them for
 * `product_pricing` — the caller's headers left out: one saved three times
 * over, each save followed by its tags and its status; one repriced from
 * 108.9 to 123.75; two of a batch import's tiers; and one saved once.
 */
export const RECORDED_PRICING_EVENTS: RecordData[] = [
  stream(
    'PP-SK-10001-10000-1',
    1,
    1784010624756,
    'saved',
    [
      '0VPKIlvQ00gp0xm',
      '0VPKIlvK00gp0xl',
      'mock-request-861e1ca36ebf75b4302e571d37ed2ac546302604',
      '0VPKIlvQ00gp0xn',
    ],
    saved('PP-SK-10001-10000-1', 'SK-10001', 1, 120, 1815546611000),
  ),
  stream(
    'PP-SK-10001-10000-1',
    2,
    1784010625310,
    'tagsApplied',
    [
      '0VPKIm4M00gp0xt',
      '0VPKIm3d00gp0xr',
      'mock-request-acdc2979afbc00dd3d68036e9f8fc4de787cc8f3',
      '0VPKIm4M00gp0xu',
    ],
    TAGS,
  ),
  stream(
    'PP-SK-10001-10000-1',
    3,
    1784010625858,
    'statusChanged',
    [
      '0VPKImDC00gp0xy',
      '0VPKImD700gp0xx',
      'mock-request-ee5d379f4469377f886a82cf68f3ba861353602a',
      '0VPKImDC00gp0xz',
    ],
    ACTIVE,
  ),
  stream(
    'PP-SK-10001-10000-1',
    4,
    1784016858265,
    'saved',
    [
      '0VPKivXt00gp1La',
      '0VPKivXo00gp1LZ',
      'mock-request-bf9dddd197688fb2fdce5d5d38f16807c4e62323',
      '0VPKivXt00gp1Lb',
    ],
    saved('PP-SK-10001-10000-1', 'SK-10001', 1, 120, 1815552840000),
  ),
  stream(
    'PP-SK-10001-10000-1',
    5,
    1784016858899,
    'tagsApplied',
    [
      '0VPKivi700gp1Lg',
      '0VPKivi100gp1Lf',
      'mock-request-b56af0cd34274adfb29ad1220de9f448b03f22ea',
      '0VPKivi700gp1Lh',
    ],
    TAGS,
  ),
  stream(
    'PP-SK-10001-10000-1',
    6,
    1784016859591,
    'statusChanged',
    [
      '0VPKivtH00gp1Lm',
      '0VPKivsL00gp1Ll',
      'mock-request-13e653f4f2878f412b34b81e660467d781ea446b',
      '0VPKivtH00gp1Ln',
    ],
    ACTIVE,
  ),
  stream(
    'PP-SK-10001-10000-1',
    7,
    1784016896052,
    'saved',
    [
      '0VPKj5NM00gp1OZ',
      '0VPKj5NH00gp1OY',
      'mock-request-7a3cf86205bc488995e3a6c60b46399827db25fa',
      '0VPKj5NM00gp1Oa',
    ],
    saved('PP-SK-10001-10000-1', 'SK-10001', 1, 120, 1815552883000),
  ),
  stream(
    'PP-SK-10001-10000-1',
    8,
    1784016896610,
    'tagsApplied',
    [
      '0VPKj5WM00gp1Og',
      '0VPKj5V800gp1Oe',
      'mock-request-e58c694bc51b248f774cc59afab88d94c1ad926d',
      '0VPKj5WM00gp1Oh',
    ],
    TAGS,
  ),
  stream(
    'PP-SK-10001-10000-1',
    9,
    1784016897110,
    'statusChanged',
    [
      '0VPKj5eR00gp1Ol',
      '0VPKj5dw00gp1Ok',
      'mock-request-8512dba03aa7210218867fa4b01b892f20287daa',
      '0VPKj5eR00gp1Om',
    ],
    ACTIVE,
  ),
  stream(
    'PP-SK-1000J-40812-1',
    1,
    1789611574587,
    'saved',
    [
      '0VVRLmpv002O63N',
      '0VVRLmpq002O63M',
      'pricing-tier-20260917-pp-sk-1000j-40812-1',
      '0VVRLmpv002O63O',
    ],
    saved('PP-SK-1000J-40812-1', 'SK-1000J', 1, 4200, 1830297599999),
  ),
  stream(
    'PP-SK-1000J-40812-10',
    1,
    1789611578716,
    'saved',
    [
      '0VVRLnuW002O63U',
      '0VVRLnuR002O63T',
      'pricing-tier-20260917-pp-sk-1000j-40812-10',
      '0VVRLnuW002O63V',
    ],
    saved('PP-SK-1000J-40812-10', 'SK-1000J', 10, 3800, 1830297599999),
  ),
  stream(
    'PP-SK-1JDF0-20000-1',
    1,
    1779981963000,
    'saved',
    [
      '0VKveuy000h200X',
      '0VKveueY00h200U',
      '0VKveuIU00h200R-0',
      '0VKveuy000h200Y',
    ],
    saved('PP-SK-1JDF0-20000-1', 'SK-1JDF0', 1, 110, 1806508799999),
  ),
  stream(
    'PP-SK-QSH6-20000-10',
    1,
    1779981962596,
    'saved',
    [
      '0VKveurV00h200V',
      '0VKvetkr00h200N',
      '0VKvesrA00h200G-2',
      '0VKveurV00h200W',
    ],
    saved('PP-SK-QSH6-20000-10', 'SK-QSH6', 10, 108.9, 1799423999999),
  ),
  stream(
    'PP-SK-QSH6-20000-10',
    2,
    1785754026104,
    'saved',
    [
      '0VREHuG000hr1de',
      '0VREHuC700hr1dU',
      '0VREHuBI00hr1dR-2',
      '0VREHuG000hr1df',
    ],
    saved('PP-SK-QSH6-20000-10', 'SK-QSH6', 10, 123.75, 1799423999999),
  ),
  stream(
    'PP-SK-QSH6-20000-10',
    3,
    1785754026558,
    'saved',
    [
      '0VREHuNK00hr1dr',
      '0VREHuLV00hr1dm',
      '0VREHuHY00hr1di-2',
      '0VREHuNK00hr1ds',
    ],
    saved('PP-SK-QSH6-20000-10', 'SK-QSH6', 10, 123.75, 1799423999999),
  ),
  stream(
    'PP-SK-QSH6-20000-10',
    4,
    1785754325607,
    'saved',
    [
      '0VREJAAh00hr1f9',
      '0VREJA7L00hr1f4',
      '0VREJA5t00hr1f0-2',
      '0VREJAAh00hr1fA',
    ],
    saved('PP-SK-QSH6-20000-10', 'SK-QSH6', 10, 123.75, 1799423999999),
  ),
];

function stream(
  aggregateId: string,
  version: number,
  createTime: number,
  kind: EventKind,
  [id, commandId, requestId, eventId]: [string, string, string, string],
  body: object,
): RecordData {
  return {
    contextName: 'pricing-service',
    aggregateName: 'product_pricing',
    header: { upstream_name: EVENT_NAME[kind] },
    aggregateId,
    tenantId: 'mydao',
    ownerId: '',
    spaceId: '',
    commandId,
    requestId,
    version,
    body: [
      {
        id: eventId,
        name: EVENT_NAME[kind],
        revision: '0.0.1',
        bodyType: PRICING_EVENT[kind],
        body,
      },
    ],
    createTime,
    size: 1,
    id,
  };
}

/**
 * Answers the snapshot queries the pricing console sends to the recorded
 * host. The console is read-only, so there is no command to answer.
 *
 * Returns the uninstaller, as `beforeEach` expects.
 */
export function installRecordedPricingService(): () => void {
  return installRecordedWowService({
    host: RECORDED_PRICING_HOST,
    resource: 'product_pricing/snapshot',
    documents: RECORDED_PRICINGS,
  });
}

/**
 * Answers the event stream queries the pricing event console sends to the
 * recorded host.
 *
 * Returns the uninstaller, as `beforeEach` expects.
 */
export function installRecordedPricingEventService(): () => void {
  return installRecordedWowService({
    host: RECORDED_PRICING_EVENTS_HOST,
    resource: 'product_pricing/event',
    documents: RECORDED_PRICING_EVENTS,
  });
}

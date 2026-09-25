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

/* --------------------------------------------------------------------------
 * 栖木生活：零售数据集的业务参考数据（docs/scenarios.md 第 2 节）。
 *
 * 这里的一切都是**手写的业务事实**，不是随机量：类目、SKU 与价格、品牌、店铺、
 * 渠道、仓库、承运商、省市与城市等级、活动日历、券模板、售后理由。手写才能保证
 * 类目名、价格带与品牌读起来是真的。随机的只有订单与买家，由 `generate.ts`
 * 按这里的参数生成。
 * ------------------------------------------------------------------------ */

// ---------------------------------------------------------------- 店铺与渠道

export const SHOPS = [
  { id: 'SHOP-FLAGSHIP', name: '栖木生活官方旗舰店' },
  { id: 'SHOP-LIVE', name: '栖木生活直播专营店' },
  { id: 'SHOP-ENTERPRISE', name: '栖木生活企业团购店' },
] as const;
export type ShopId = (typeof SHOPS)[number]['id'];

export const CHANNELS = [
  { id: 'APP', name: '自有 App' },
  { id: 'MINI_PROGRAM', name: '微信小程序' },
  { id: 'PC', name: 'PC 商城' },
  { id: 'LIVE', name: '直播间' },
  { id: 'DISTRIBUTION', name: '分销' },
] as const;
export type Channel = (typeof CHANNELS)[number]['id'];

/**
 * 渠道结构：2024-09 与 2026-09 两端的份额（%），其间按月线性变化。增长主要
 * 来自直播，PC 在萎缩（第 2.5 节「渠道」）。
 */
export const CHANNEL_MIX: Record<
  Channel,
  { readonly start: number; readonly end: number }
> = {
  APP: { start: 40, end: 35 },
  MINI_PROGRAM: { start: 30, end: 28 },
  PC: { start: 12, end: 7 },
  LIVE: { start: 8, end: 20 },
  DISTRIBUTION: { start: 10, end: 10 },
};

/**
 * 售后与付款后取消的倍率：直播冲动消费退得多（退款率约 14%），PC 约 4%。
 */
export const CHANNEL_RETURN_FACTOR: Record<Channel, number> = {
  APP: 0.7,
  MINI_PROGRAM: 0.75,
  PC: 0.4,
  LIVE: 1.9,
  DISTRIBUTION: 0.55,
};

// ---------------------------------------------------------------- 仓库与承运商

export const WAREHOUSES = [
  { id: 'EAST', name: '华东（嘉兴）' },
  { id: 'NORTH', name: '华北（天津）' },
  { id: 'SOUTH', name: '华南（东莞）' },
  { id: 'SOUTHWEST', name: '西南（成都）' },
] as const;
export type WarehouseId = (typeof WAREHOUSES)[number]['id'];

/** 小家电只从华南仓发。 */
export const APPLIANCE_WAREHOUSE: WarehouseId = 'SOUTH';

export const CARRIERS = [
  // 发货到签收的中位小时数（第 2.5 节「履约」），偏远省份再加 48 小时。
  { id: 'ZTO', name: '中通快递', weight: 32, signHours: 52, prefix: '78' },
  { id: 'YTO', name: '圆通速递', weight: 26, signHours: 55, prefix: 'YT' },
  { id: 'SF', name: '顺丰速运', weight: 18, signHours: 26, prefix: 'SF' },
  { id: 'JD', name: '京东物流', weight: 14, signHours: 30, prefix: 'JDV' },
  { id: 'EMS', name: '中国邮政 EMS', weight: 10, signHours: 70, prefix: 'EM' },
] as const;
export type CarrierId = (typeof CARRIERS)[number]['id'];

/** 偏远省份的签收再加这么多小时。 */
export const REMOTE_EXTRA_HOURS = 48;

// ---------------------------------------------------------------- 地域

export type CityTier = 'TIER_1' | 'NEW_TIER_1' | 'TIER_2' | 'TIER_3_BELOW';

export const CITY_TIERS: Record<CityTier, string> = {
  TIER_1: '一线',
  NEW_TIER_1: '新一线',
  TIER_2: '二线',
  TIER_3_BELOW: '三线及以下',
};

export interface City {
  readonly name: string;
  readonly tier: CityTier;
  /** 在本省的份额（相对值）。 */
  readonly weight: number;
  readonly districts: readonly string[];
}

export interface Province {
  readonly name: string;
  /** 在全部订单里的份额（%）。 */
  readonly weight: number;
  /** 就近发货的仓库。 */
  readonly warehouse: WarehouseId;
  /** 签收更慢的偏远省份。 */
  readonly remote?: true;
  readonly cities: readonly City[];
}

function city(
  name: string,
  tier: CityTier,
  weight: number,
  ...districts: string[]
): City {
  return { name, tier, weight, districts };
}

const T1: CityTier = 'TIER_1';
const N1: CityTier = 'NEW_TIER_1';
const T2: CityTier = 'TIER_2';
const T3: CityTier = 'TIER_3_BELOW';

/** 按省份加权：广东、浙江、江苏领先，新疆、西藏在尾部。 */
export const PROVINCES: readonly Province[] = [
  {
    name: '广东省',
    weight: 14,
    warehouse: 'SOUTH',
    cities: [
      city('广州市', T1, 32, '天河区', '海珠区', '番禺区', '白云区'),
      city('深圳市', T1, 32, '南山区', '福田区', '宝安区', '龙岗区'),
      city('东莞市', N1, 10, '南城街道', '松山湖', '长安镇'),
      city('佛山市', T2, 10, '禅城区', '顺德区', '南海区'),
      city('汕头市', T3, 8, '金平区', '龙湖区'),
      city('湛江市', T3, 8, '赤坎区', '霞山区'),
    ],
  },
  {
    name: '浙江省',
    weight: 11,
    warehouse: 'EAST',
    cities: [
      city('杭州市', N1, 40, '西湖区', '余杭区', '滨江区', '拱墅区'),
      city('宁波市', N1, 16, '鄞州区', '海曙区', '北仑区'),
      city('温州市', T2, 14, '鹿城区', '瓯海区'),
      city('金华市', T2, 12, '婺城区', '义乌市'),
      city('湖州市', T3, 18, '吴兴区', '德清县', '长兴县'),
    ],
  },
  {
    name: '江苏省',
    weight: 10,
    warehouse: 'EAST',
    cities: [
      city('南京市', N1, 26, '鼓楼区', '建邺区', '江宁区'),
      city('苏州市', N1, 26, '姑苏区', '工业园区', '吴中区'),
      city('无锡市', T2, 14, '梁溪区', '滨湖区'),
      city('常州市', T2, 12, '天宁区', '武进区'),
      city('盐城市', T3, 22, '亭湖区', '盐都区', '东台市'),
    ],
  },
  {
    name: '上海市',
    weight: 7,
    warehouse: 'EAST',
    cities: [
      city('上海市', T1, 1, '浦东新区', '徐汇区', '闵行区', '静安区', '杨浦区'),
    ],
  },
  {
    name: '北京市',
    weight: 6,
    warehouse: 'NORTH',
    cities: [
      city('北京市', T1, 1, '朝阳区', '海淀区', '丰台区', '西城区', '通州区'),
    ],
  },
  {
    name: '山东省',
    weight: 6,
    warehouse: 'NORTH',
    cities: [
      city('青岛市', N1, 28, '市南区', '崂山区', '黄岛区'),
      city('济南市', T2, 26, '历下区', '槐荫区'),
      city('烟台市', T2, 14, '芝罘区', '莱山区'),
      city('临沂市', T3, 32, '兰山区', '罗庄区', '沂南县'),
    ],
  },
  {
    name: '四川省',
    weight: 5,
    warehouse: 'SOUTHWEST',
    cities: [
      city('成都市', N1, 60, '武侯区', '锦江区', '高新区', '双流区'),
      city('绵阳市', T3, 20, '涪城区', '游仙区'),
      city('南充市', T3, 20, '顺庆区', '高坪区'),
    ],
  },
  {
    name: '福建省',
    weight: 5,
    warehouse: 'SOUTH',
    cities: [
      city('厦门市', T2, 34, '思明区', '湖里区'),
      city('福州市', T2, 30, '鼓楼区', '仓山区'),
      city('泉州市', T2, 20, '丰泽区', '晋江市'),
      city('莆田市', T3, 16, '城厢区', '荔城区'),
    ],
  },
  {
    name: '湖北省',
    weight: 4,
    warehouse: 'EAST',
    cities: [
      city('武汉市', N1, 60, '武昌区', '洪山区', '江汉区'),
      city('宜昌市', T3, 20, '西陵区', '伍家岗区'),
      city('襄阳市', T3, 20, '襄城区', '樊城区'),
    ],
  },
  {
    name: '河南省',
    weight: 3.5,
    warehouse: 'NORTH',
    cities: [
      city('郑州市', N1, 50, '金水区', '中原区', '郑东新区'),
      city('洛阳市', T3, 25, '洛龙区', '西工区'),
      city('南阳市', T3, 25, '卧龙区', '宛城区'),
    ],
  },
  {
    name: '湖南省',
    weight: 3.2,
    warehouse: 'SOUTH',
    cities: [
      city('长沙市', N1, 55, '岳麓区', '芙蓉区', '雨花区'),
      city('株洲市', T3, 20, '天元区', '芦淞区'),
      city('岳阳市', T3, 25, '岳阳楼区', '云溪区'),
    ],
  },
  {
    name: '安徽省',
    weight: 3,
    warehouse: 'EAST',
    cities: [
      city('合肥市', N1, 55, '蜀山区', '包河区', '庐阳区'),
      city('芜湖市', T3, 20, '镜湖区', '鸠江区'),
      city('阜阳市', T3, 25, '颍州区', '颍泉区'),
    ],
  },
  {
    name: '河北省',
    weight: 2.8,
    warehouse: 'NORTH',
    cities: [
      city('石家庄市', T2, 40, '长安区', '桥西区'),
      city('保定市', T3, 30, '竞秀区', '莲池区'),
      city('唐山市', T3, 30, '路北区', '路南区'),
    ],
  },
  {
    name: '重庆市',
    weight: 2.6,
    warehouse: 'SOUTHWEST',
    cities: [city('重庆市', N1, 1, '渝北区', '江北区', '南岸区', '九龙坡区')],
  },
  {
    name: '陕西省',
    weight: 2.2,
    warehouse: 'SOUTHWEST',
    cities: [
      city('西安市', N1, 75, '雁塔区', '碑林区', '未央区'),
      city('宝鸡市', T3, 25, '渭滨区', '金台区'),
    ],
  },
  {
    name: '辽宁省',
    weight: 2,
    warehouse: 'NORTH',
    cities: [
      city('沈阳市', T2, 45, '和平区', '浑南区'),
      city('大连市', T2, 40, '中山区', '甘井子区'),
      city('鞍山市', T3, 15, '铁东区', '立山区'),
    ],
  },
  {
    name: '天津市',
    weight: 1.8,
    warehouse: 'NORTH',
    cities: [city('天津市', N1, 1, '南开区', '和平区', '滨海新区')],
  },
  {
    name: '江西省',
    weight: 1.6,
    warehouse: 'EAST',
    cities: [
      city('南昌市', T2, 60, '红谷滩区', '东湖区'),
      city('赣州市', T3, 40, '章贡区', '南康区'),
    ],
  },
  {
    name: '广西壮族自治区',
    weight: 1.5,
    warehouse: 'SOUTH',
    cities: [
      city('南宁市', T2, 55, '青秀区', '西乡塘区'),
      city('桂林市', T3, 25, '七星区', '象山区'),
      city('柳州市', T3, 20, '城中区', '鱼峰区'),
    ],
  },
  {
    name: '云南省',
    weight: 1.4,
    warehouse: 'SOUTHWEST',
    cities: [
      city('昆明市', N1, 70, '五华区', '盘龙区', '官渡区'),
      city('大理白族自治州', T3, 30, '大理市', '祥云县'),
    ],
  },
  {
    name: '山西省',
    weight: 1.1,
    warehouse: 'NORTH',
    cities: [
      city('太原市', T2, 60, '小店区', '迎泽区'),
      city('大同市', T3, 40, '平城区', '云冈区'),
    ],
  },
  {
    name: '黑龙江省',
    weight: 1,
    warehouse: 'NORTH',
    cities: [
      city('哈尔滨市', T2, 70, '南岗区', '道里区'),
      city('大庆市', T3, 30, '萨尔图区', '让胡路区'),
    ],
  },
  {
    name: '贵州省',
    weight: 0.9,
    warehouse: 'SOUTHWEST',
    cities: [
      city('贵阳市', T2, 70, '观山湖区', '南明区'),
      city('遵义市', T3, 30, '红花岗区', '汇川区'),
    ],
  },
  {
    name: '吉林省',
    weight: 0.8,
    warehouse: 'NORTH',
    cities: [
      city('长春市', T2, 70, '朝阳区', '南关区'),
      city('吉林市', T3, 30, '船营区', '丰满区'),
    ],
  },
  {
    name: '内蒙古自治区',
    weight: 0.6,
    warehouse: 'NORTH',
    cities: [
      city('呼和浩特市', T3, 60, '新城区', '赛罕区'),
      city('包头市', T3, 40, '昆都仑区', '青山区'),
    ],
  },
  {
    name: '海南省',
    weight: 0.5,
    warehouse: 'SOUTH',
    cities: [
      city('海口市', T3, 60, '龙华区', '美兰区'),
      city('三亚市', T3, 40, '吉阳区', '天涯区'),
    ],
  },
  {
    name: '甘肃省',
    weight: 0.5,
    warehouse: 'SOUTHWEST',
    cities: [
      city('兰州市', T2, 80, '城关区', '七里河区'),
      city('天水市', T3, 20, '秦州区', '麦积区'),
    ],
  },
  {
    name: '新疆维吾尔自治区',
    weight: 0.4,
    warehouse: 'SOUTHWEST',
    remote: true,
    cities: [
      city('乌鲁木齐市', T3, 80, '天山区', '沙依巴克区'),
      city('喀什地区', T3, 20, '喀什市', '疏勒县'),
    ],
  },
  {
    name: '宁夏回族自治区',
    weight: 0.2,
    warehouse: 'SOUTHWEST',
    cities: [city('银川市', T3, 1, '兴庆区', '金凤区')],
  },
  {
    name: '青海省',
    weight: 0.15,
    warehouse: 'SOUTHWEST',
    remote: true,
    cities: [city('西宁市', T3, 1, '城西区', '城中区')],
  },
  {
    name: '西藏自治区',
    weight: 0.1,
    warehouse: 'SOUTHWEST',
    remote: true,
    cities: [city('拉萨市', T3, 1, '城关区', '堆龙德庆区')],
  },
];

// ---------------------------------------------------------------- 类目与商品

export const CATEGORIES = [
  {
    name: '床品布艺',
    children: ['床品四件套', '被芯枕芯', '毛巾浴巾'],
    // 尺寸与颜色不合适，退货偏多。
    returnFactor: 1.6,
  },
  {
    name: '厨房餐具',
    children: ['锅具', '餐具', '保鲜收纳'],
    returnFactor: 0.9,
  },
  {
    name: '家居收纳',
    children: ['收纳箱', '衣物收纳', '桌面收纳'],
    returnFactor: 0.8,
  },
  {
    name: '清洁日化',
    children: ['清洁工具', '洗护日化', '香氛'],
    returnFactor: 0.6,
  },
  {
    name: '家居装饰',
    children: ['灯具', '花瓶摆件', '地毯地垫'],
    returnFactor: 1,
  },
  {
    name: '小家电',
    children: ['厨房小电', '生活小电', '个护小电'],
    returnFactor: 1.1,
  },
] as const;
export type Category1 = (typeof CATEGORIES)[number]['name'];

/** 自有品牌占七成，另有三个联营品牌。 */
export const BRANDS = ['栖木', '禾野', '拾光', '暖屿'] as const;
export type Brand = (typeof BRANDS)[number];

export type PriceBand =
  '<50' | '50-100' | '100-200' | '200-500' | '500-1000' | '>=1000';

export function priceBandOf(price: number): PriceBand {
  if (price < 50) return '<50';
  if (price < 100) return '50-100';
  if (price < 200) return '100-200';
  if (price < 500) return '200-500';
  if (price < 1000) return '500-1000';
  return '>=1000';
}

interface SpuRow {
  readonly title: string;
  readonly category1: Category1;
  readonly category2: string;
  readonly brand: Brand;
  /** 首个规格的标价（元）。 */
  readonly price: number;
  /** 热度 1～10：决定 SKU 的销量排名。 */
  readonly heat: number;
  /** 包裹重量（千克）。 */
  readonly weightKg: number;
  /** 四个规格：名字与相对首个规格的价格倍数。 */
  readonly variants: readonly (readonly [string, number])[];
}

const SIZES_BED: SpuRow['variants'] = [
  ['1.5m 床 · 燕麦', 1],
  ['1.8m 床 · 燕麦', 1.15],
  ['1.8m 床 · 雾蓝', 1.15],
  ['2.0m 床 · 雾蓝', 1.3],
];
const COLORS: SpuRow['variants'] = [
  ['米白', 1],
  ['燕麦', 1],
  ['雾蓝', 1],
  ['烟灰', 1],
];
const PACKS: SpuRow['variants'] = [
  ['单只装', 1],
  ['两只装', 1.8],
  ['四只装', 3.2],
  ['家庭装', 5],
];
const SIZES: SpuRow['variants'] = [
  ['小号', 1],
  ['中号', 1.3],
  ['大号', 1.6],
  ['加大号', 2],
];
const EDITIONS: SpuRow['variants'] = [
  ['标准版', 1],
  ['标准版 · 岩灰', 1],
  ['升级版', 1.25],
  ['礼盒版', 1.4],
];

function spu(
  title: string,
  category1: Category1,
  category2: string,
  brand: Brand,
  price: number,
  heat: number,
  weightKg: number,
  variants: SpuRow['variants'],
): SpuRow {
  return {
    title,
    category1,
    category2,
    brand,
    price,
    heat,
    weightKg,
    variants,
  };
}

/** 60 个 SPU，每个四个规格，共 240 个 SKU。 */
const SPUS: readonly SpuRow[] = [
  // 床品布艺
  spu('水洗棉四件套', '床品布艺', '床品四件套', '栖木', 299, 6, 2.2, SIZES_BED),
  spu('天丝四件套', '床品布艺', '床品四件套', '禾野', 599, 3, 2, SIZES_BED),
  spu(
    '磨毛纯棉四件套',
    '床品布艺',
    '床品四件套',
    '栖木',
    399,
    4,
    2.4,
    SIZES_BED,
  ),
  spu('大豆纤维被', '床品布艺', '被芯枕芯', '栖木', 399, 4, 3, SIZES_BED),
  spu('白鹅绒羽绒被', '床品布艺', '被芯枕芯', '禾野', 899, 2, 2.5, SIZES_BED),
  spu('天然乳胶枕', '床品布艺', '被芯枕芯', '禾野', 199, 5, 1.3, [
    ['标准款', 1],
    ['高低款', 1.1],
    ['儿童款', 0.7],
    ['双只装', 1.85],
  ]),
  spu('荞麦枕', '床品布艺', '被芯枕芯', '栖木', 89, 3, 1.5, [
    ['标准款', 1],
    ['颈椎款', 1.2],
    ['儿童款', 0.7],
    ['双只装', 1.85],
  ]),
  spu('竹纤维浴巾', '床品布艺', '毛巾浴巾', '栖木', 79, 9, 0.5, [
    ['70×140 · 米白', 1],
    ['70×140 · 雾蓝', 1],
    ['80×160 · 米白', 1.3],
    ['80×160 · 雾蓝', 1.3],
  ]),
  spu('长绒棉毛巾', '床品布艺', '毛巾浴巾', '栖木', 45, 10, 0.2, PACKS),
  spu('儿童浴巾', '床品布艺', '毛巾浴巾', '栖木', 49, 4, 0.4, COLORS),
  spu('华夫格浴袍', '床品布艺', '毛巾浴巾', '禾野', 159, 2, 0.9, [
    ['S', 1],
    ['M', 1],
    ['L', 1],
    ['XL', 1.1],
  ]),
  // 厨房餐具
  spu('不粘炒锅', '厨房餐具', '锅具', '栖木', 199, 6, 1.8, [
    ['28cm', 1],
    ['30cm', 1.1],
    ['32cm', 1.2],
    ['30cm 带盖', 1.3],
  ]),
  spu('铸铁珐琅锅', '厨房餐具', '锅具', '栖木', 459, 3, 4.5, [
    ['20cm · 樱桃红', 1],
    ['22cm · 樱桃红', 1.15],
    ['22cm · 奶白', 1.15],
    ['24cm · 奶白', 1.3],
  ]),
  spu('不锈钢汤锅', '厨房餐具', '锅具', '栖木', 239, 3, 2.2, [
    ['20cm', 1],
    ['22cm', 1.1],
    ['24cm', 1.2],
    ['26cm', 1.3],
  ]),
  spu('陶瓷餐具套装', '厨房餐具', '餐具', '栖木', 259, 5, 4, [
    ['两人食', 1],
    ['四人食', 1.6],
    ['六人食', 2.2],
    ['礼盒装', 2.6],
  ]),
  spu('釉下彩饭碗', '厨房餐具', '餐具', '栖木', 49, 8, 0.8, PACKS),
  spu('鸡翅木筷子', '厨房餐具', '餐具', '栖木', 39, 7, 0.3, [
    ['五双装', 1],
    ['十双装', 1.8],
    ['礼盒装', 2.2],
    ['儿童款', 0.7],
  ]),
  spu('双层玻璃杯', '厨房餐具', '餐具', '栖木', 49, 9, 0.4, PACKS),
  spu('玻璃保鲜盒', '厨房餐具', '保鲜收纳', '栖木', 89, 7, 1.6, [
    ['三件套', 1],
    ['五件套', 1.5],
    ['七件套', 2],
    ['便当款', 0.7],
  ]),
  spu('玻璃密封罐', '厨房餐具', '保鲜收纳', '栖木', 45, 6, 1, PACKS),
  spu('硅胶保鲜袋', '厨房餐具', '保鲜收纳', '栖木', 39, 5, 0.3, SIZES),
  // 家居收纳
  spu('折叠收纳箱', '家居收纳', '收纳箱', '栖木', 69, 8, 1.4, SIZES),
  spu('透明整理箱', '家居收纳', '收纳箱', '栖木', 89, 6, 2, SIZES),
  spu('藤编收纳筐', '家居收纳', '收纳箱', '拾光', 79, 3, 0.8, SIZES),
  spu('无痕植绒衣架', '家居收纳', '衣物收纳', '栖木', 49, 9, 1.2, [
    ['10 只装', 1],
    ['20 只装', 1.8],
    ['30 只装', 2.5],
    ['儿童 20 只', 1.5],
  ]),
  spu('真空压缩袋', '家居收纳', '衣物收纳', '栖木', 49, 6, 0.8, SIZES),
  spu('抽屉分格盒', '家居收纳', '衣物收纳', '栖木', 35, 5, 0.5, SIZES),
  spu('桌面收纳架', '家居收纳', '桌面收纳', '栖木', 69, 4, 1.1, COLORS),
  spu('化妆品收纳盒', '家居收纳', '桌面收纳', '栖木', 59, 5, 0.9, COLORS),
  spu('线缆收纳盒', '家居收纳', '桌面收纳', '栖木', 45, 3, 0.6, COLORS),
  // 清洁日化
  spu('平板拖把', '清洁日化', '清洁工具', '栖木', 129, 5, 1.6, [
    ['标准款', 1],
    ['加替换布 2 片', 1.2],
    ['加替换布 4 片', 1.4],
    ['免手洗款', 1.6],
  ]),
  spu('静电除尘掸', '清洁日化', '清洁工具', '栖木', 35, 6, 0.3, PACKS),
  spu('木浆百洁布', '清洁日化', '清洁工具', '栖木', 29, 8, 0.2, PACKS),
  spu('植萃洗衣液', '清洁日化', '洗护日化', '栖木', 69, 9, 2.2, [
    ['2kg', 1],
    ['3kg', 1.4],
    ['2kg×2', 1.9],
    ['补充装 1kg', 0.55],
  ]),
  spu('果蔬洗洁精', '清洁日化', '洗护日化', '栖木', 29, 7, 1.1, PACKS),
  spu('衣物柔顺剂', '清洁日化', '洗护日化', '栖木', 39, 5, 1.6, PACKS),
  spu('泡沫洗手液', '清洁日化', '洗护日化', '栖木', 35, 6, 0.6, PACKS),
  spu('香薰蜡烛', '清洁日化', '香氛', '栖木', 89, 4, 0.5, [
    ['雪松', 1],
    ['白茶', 1],
    ['无花果', 1],
    ['礼盒三只', 2.6],
  ]),
  spu('无火香薰', '清洁日化', '香氛', '栖木', 69, 4, 0.5, [
    ['雪松', 1],
    ['白茶', 1],
    ['无花果', 1],
    ['补充液', 0.6],
  ]),
  spu('车载香片', '清洁日化', '香氛', '栖木', 29, 3, 0.1, PACKS),
  // 家居装饰
  spu('床头氛围灯', '家居装饰', '灯具', '栖木', 129, 4, 0.9, COLORS),
  spu('胡桃木落地灯', '家居装饰', '灯具', '拾光', 399, 2, 5, [
    ['标准款', 1],
    ['可调光', 1.2],
    ['可调光 · 暖白', 1.2],
    ['智能款', 1.5],
  ]),
  spu('护眼台灯', '家居装饰', '灯具', '栖木', 259, 3, 1.5, EDITIONS),
  spu('陶瓷花瓶', '家居装饰', '花瓶摆件', '拾光', 89, 4, 1, SIZES),
  spu('永生干花花束', '家居装饰', '花瓶摆件', '栖木', 59, 5, 0.3, COLORS),
  spu('木质摆件', '家居装饰', '花瓶摆件', '栖木', 69, 3, 0.5, [
    ['小鹿', 1],
    ['山峦', 1],
    ['猫咪', 1],
    ['组合三件', 2.4],
  ]),
  spu('装饰画', '家居装饰', '花瓶摆件', '拾光', 129, 3, 2, SIZES),
  spu('客厅地毯', '家居装饰', '地毯地垫', '拾光', 459, 2, 6, [
    ['140×200', 1],
    ['160×230', 1.3],
    ['200×300', 1.9],
    ['圆形 150', 0.9],
  ]),
  spu('硅藻泥吸水地垫', '家居装饰', '地毯地垫', '栖木', 49, 7, 1.2, SIZES),
  spu('入户地垫', '家居装饰', '地毯地垫', '栖木', 59, 4, 1, SIZES),
  // 小家电（暖屿）
  spu('智能电饭煲', '小家电', '厨房小电', '暖屿', 499, 3, 4.2, [
    ['3L', 1],
    ['4L', 1.2],
    ['5L', 1.4],
    ['4L IH 版', 1.8],
  ]),
  spu('空气炸锅', '小家电', '厨房小电', '暖屿', 399, 5, 4.5, [
    ['4.5L', 1],
    ['5.5L', 1.2],
    ['6.5L 可视', 1.5],
    ['双篮款', 2],
  ]),
  spu('静音破壁机', '小家电', '厨房小电', '暖屿', 899, 2, 5.5, EDITIONS),
  spu('半自动咖啡机', '小家电', '厨房小电', '暖屿', 1599, 1, 7, EDITIONS),
  spu('除螨仪', '小家电', '生活小电', '暖屿', 399, 3, 2.2, EDITIONS),
  spu('空气净化器', '小家电', '生活小电', '暖屿', 1299, 1, 8.5, EDITIONS),
  spu('冷雾加湿器', '小家电', '生活小电', '暖屿', 199, 4, 1.8, EDITIONS),
  spu('声波电动牙刷', '小家电', '个护小电', '暖屿', 299, 4, 0.6, EDITIONS),
  spu('高速吹风机', '小家电', '个护小电', '暖屿', 599, 3, 1.2, EDITIONS),
  spu('手持挂烫机', '小家电', '个护小电', '暖屿', 399, 2, 1.6, EDITIONS),
];

export interface Sku {
  readonly skuId: string;
  readonly spuId: string;
  /** 商品标题：SPU 名加规格，就是订单行里的 `title`。 */
  readonly title: string;
  readonly category1: Category1;
  readonly category2: string;
  readonly brand: Brand;
  readonly listPrice: number;
  readonly priceBand: PriceBand;
  readonly weightKg: number;
  readonly isAppliance: boolean;
  /** 销量排名，从 1 起；生成器按它取齐夫权重。 */
  readonly rank: number;
}

const VARIANT_HEAT = [1, 0.6, 0.4, 0.25];

/** 规格价格取到 .9 结尾的整数元，像真实的标价。 */
function listPriceOf(base: number, factor: number): number {
  const raw = base * factor;
  return raw < 20 ? Math.round(raw) - 0.1 : Math.round(raw / 10) * 10 - 1;
}

function buildSkus(): readonly Sku[] {
  const variants = SPUS.flatMap((row, spuIndex) =>
    row.variants.map(([name, factor], variantIndex) => ({
      row,
      spuId: `SPU-${String(spuIndex + 1).padStart(3, '0')}`,
      name,
      factor,
      variantIndex,
      // 热度高的 SPU、排在前面的规格卖得多；按这个分数排出销量排名。
      score: row.heat * VARIANT_HEAT[variantIndex],
    })),
  );
  const ranked = variants
    .map((variant, index) => ({ score: variant.score, index }))
    .sort((a, b) => b.score - a.score || a.index - b.index);
  const rankOf = new Map(ranked.map(({ index }, rank) => [index, rank + 1]));
  return variants.map(
    ({ row, spuId, name, factor, variantIndex }, index): Sku => {
      const listPrice = listPriceOf(row.price, factor);
      return {
        skuId: `${spuId}-${variantIndex + 1}`,
        spuId,
        title: `${row.title} ${name}`,
        category1: row.category1,
        category2: row.category2,
        brand: row.brand,
        listPrice,
        priceBand: priceBandOf(listPrice),
        weightKg: Math.round(row.weightKg * Math.max(1, factor) * 10) / 10,
        isAppliance: row.category1 === '小家电',
        rank: rankOf.get(index)!,
      };
    },
  );
}

export const SKUS: readonly Sku[] = buildSkus();

export const SKU_BY_ID: ReadonlyMap<string, Sku> = new Map(
  SKUS.map(sku => [sku.skuId, sku]),
);

/** A1 的那个浴巾 SKU：竹纤维浴巾 70×140。 */
export const BATH_TOWEL_SKU_ID = SKUS.find(
  sku => sku.title === '竹纤维浴巾 70×140 · 米白',
)!.skuId;

/**
 * SKU 销量按排名服从齐夫–曼德尔布罗分布 `(rank + q)^-s`：前 10 个 SKU 约占
 * 件数的 35%，后 150 个合计不到 10%。纯齐夫（q = 0）在 240 个 SKU 上做不到
 * 这两条同时成立，所以加了平移量 q。
 */
export const SKU_POPULARITY = { s: 2.5, q: 30 } as const;

// ---------------------------------------------------------------- 会员

export const MEMBER_LEVELS = [
  // 份额（%）与回购强度的倍率：黑卡约贡献 25% 的 GMV。
  { id: 'REGULAR', name: '普通', weight: 55, intensity: 1 },
  { id: 'SILVER', name: '银卡', weight: 25, intensity: 1.6 },
  { id: 'GOLD', name: '金卡', weight: 14, intensity: 2.6 },
  { id: 'BLACK', name: '黑卡', weight: 6, intensity: 12 },
] as const;
export type MemberLevel = (typeof MEMBER_LEVELS)[number]['id'];

// ---------------------------------------------------------------- 支付

export const PAYMENT_METHODS = [
  { id: 'WECHAT_PAY', name: '微信支付' },
  { id: 'ALIPAY', name: '支付宝' },
  { id: 'CREDIT_INSTALLMENT', name: '信用卡分期' },
  { id: 'UNIONPAY', name: '云闪付' },
  { id: 'GIFT_CARD', name: '礼品卡' },
] as const;
export type PaymentMethod = (typeof PAYMENT_METHODS)[number]['id'];

/** 分期只出现在 ¥500 以上的单。 */
export const INSTALLMENT_THRESHOLD = 500;

/** 付款方式的权重：一张单实付不足 ¥500 时没有分期。 */
export const PAYMENT_WEIGHTS: {
  readonly small: Record<PaymentMethod, number>;
  readonly large: Record<PaymentMethod, number>;
} = {
  small: {
    WECHAT_PAY: 51,
    ALIPAY: 40,
    CREDIT_INSTALLMENT: 0,
    UNIONPAY: 4.5,
    GIFT_CARD: 4.5,
  },
  large: {
    WECHAT_PAY: 20,
    ALIPAY: 20,
    CREDIT_INSTALLMENT: 55,
    UNIONPAY: 3,
    GIFT_CARD: 2,
  },
};

export const INSTALLMENTS = [3, 6, 12] as const;

// ---------------------------------------------------------------- 活动与券

export interface Activity {
  /** 如 `DOUBLE_11_2025`。 */
  readonly id: string;
  readonly name: string;
  /** 活动期，含首尾两天，`YYYY-MM-DD`（上海时间）。 */
  readonly start: string;
  readonly end: string;
  /** 活动期内日单量的倍率。 */
  readonly windowFactor: number;
  /** 主会场那一天（活动最后一天）在 `windowFactor` 之上再乘的倍率。 */
  readonly peakFactor: number;
}

function yearly(
  key: string,
  name: string,
  start: string,
  end: string,
  windowFactor: number,
  peakFactor: number,
): Activity[] {
  return [2024, 2025, 2026].map(year => ({
    id: `${key}_${year}`,
    name: `${year} ${name}`,
    start: `${year}-${start}`,
    end: `${year}-${end}`,
    windowFactor,
    peakFactor,
  }));
}

/**
 * 活动日历（第 2.5 节「日单量」）。月季节系数另见 `MONTH_FACTORS`；活动期内
 * 的倍率乘在月季节系数之上。
 */
export const ACTIVITIES: readonly Activity[] = [
  ...yearly('NEW_YEAR_FESTIVAL', '年货节', '01-05', '01-20', 1, 1.2),
  ...yearly('QUEEN_DAY', '女王节', '03-01', '03-08', 1, 2),
  ...yearly('MID_YEAR_618', '618', '05-31', '06-18', 1.35, 3.5),
  ...yearly('SALE_99', '99 划算节', '09-01', '09-09', 1, 1.6),
  ...yearly('DOUBLE_11', '双 11', '10-31', '11-11', 1.6, 6),
  ...yearly('DOUBLE_12', '双 12', '12-01', '12-12', 1, 2),
];

/** 月季节系数，下标是月份减一。 */
export const MONTH_FACTORS = [
  1.1, 1, 1, 1, 1, 1, 0.85, 0.85, 0.95, 1, 1, 1.15,
] as const;

/** 春节（正月初一）：前后各三天共一周，日单量减半。 */
export const SPRING_FESTIVALS = ['2025-01-29', '2026-02-17'] as const;
export const SPRING_FESTIVAL_FACTOR = 0.5;

/** 星期系数，下标 0 是周日。 */
export const WEEKDAY_FACTORS = [1.12, 0.95, 0.96, 0.97, 0.98, 1, 1.08] as const;

/**
 * 时段权重：10～12 点一个小峰，20～23 点是主峰，3～6 点是谷底。双 11 当天
 * 0 点那一格另算，占全天 12%。
 */
export const HOUR_WEIGHTS = [
  2.2, 1.2, 0.6, 0.3, 0.25, 0.3, 0.7, 1.5, 2.5, 3.5, 5, 5.2, 4.5, 3.8, 3.8, 3.9,
  4, 4, 4.2, 5, 7, 8, 7.5, 5,
] as const;
export const DOUBLE_11_MIDNIGHT_SHARE = 0.12;

export interface CouponTemplate {
  readonly id: string;
  readonly kind: 'SHOP' | 'PLATFORM';
  readonly name: string;
  /** 门槛与面额（元）。 */
  readonly threshold: number;
  readonly amount: number;
}

export const COUPONS: readonly CouponTemplate[] = [
  {
    id: 'SC-99-10',
    kind: 'SHOP',
    name: '店铺券 满99减10',
    threshold: 99,
    amount: 10,
  },
  {
    id: 'SC-199-20',
    kind: 'SHOP',
    name: '店铺券 满199减20',
    threshold: 199,
    amount: 20,
  },
  {
    id: 'SC-399-50',
    kind: 'SHOP',
    name: '店铺券 满399减50',
    threshold: 399,
    amount: 50,
  },
  {
    id: 'PC-200-25',
    kind: 'PLATFORM',
    name: '平台券 满200减25',
    threshold: 200,
    amount: 25,
  },
  {
    id: 'PC-500-60',
    kind: 'PLATFORM',
    name: '平台券 满500减60',
    threshold: 500,
    amount: 60,
  },
  {
    id: 'PC-LIVE-STACK',
    kind: 'PLATFORM',
    name: '直播间叠加券',
    threshold: 0,
    amount: 0,
  },
];

/** 大促期间满减：每满 300 减 40。 */
export const FULL_REDUCTION = { every: 300, minus: 40 } as const;

/** 满 ¥99 包邮，否则运费在这几档里取。 */
export const FREE_SHIPPING_THRESHOLD = 99;
export const FREIGHTS = [8, 10, 12] as const;

// ---------------------------------------------------------------- 状态与售后

export const ORDER_STATUSES = [
  { id: 'PENDING_PAYMENT', name: '待付款' },
  { id: 'PAID', name: '待发货' },
  { id: 'PARTIALLY_SHIPPED', name: '部分发货' },
  { id: 'SHIPPED', name: '已发货' },
  { id: 'SIGNED', name: '已签收' },
  { id: 'COMPLETED', name: '交易完成' },
  { id: 'CANCELLED', name: '已取消' },
  { id: 'CLOSED', name: '已关闭' },
] as const;
export type OrderStatus = (typeof ORDER_STATUSES)[number]['id'];

export const CANCEL_REASONS = [
  { id: 'PAYMENT_TIMEOUT', name: '超时未付' },
  { id: 'BUYER_CANCELLED', name: '买家取消' },
  { id: 'OUT_OF_STOCK', name: '缺货' },
  { id: 'RISK_CONTROL', name: '风控' },
] as const;
export type CancelReason = (typeof CANCEL_REASONS)[number]['id'];

export const AFTER_SALE_TYPES = [
  { id: 'REFUND_ONLY', name: '仅退款' },
  { id: 'RETURN_REFUND', name: '退货退款' },
  { id: 'EXCHANGE', name: '换货' },
  { id: 'REJECTION', name: '拒收' },
] as const;
export type AfterSaleType = (typeof AFTER_SALE_TYPES)[number]['id'];

export const AFTER_SALE_REASONS = [
  { id: 'NO_REASON_7_DAYS', name: '七天无理由' },
  { id: 'SIZE_COLOR_MISMATCH', name: '尺寸或颜色不符' },
  { id: 'QUALITY_ISSUE', name: '质量问题' },
  { id: 'MISSING_OR_WRONG', name: '少件错件' },
  { id: 'DAMAGED_IN_TRANSIT', name: '物流破损' },
  { id: 'LOST_IN_TRANSIT', name: '丢件或退回' },
  { id: 'REJECTED_ON_DELIVERY', name: '拒收' },
] as const;
export type AfterSaleReason = (typeof AFTER_SALE_REASONS)[number]['id'];

/**
 * 每张子单走售后的概率（%）：仅退款 3.5、退货退款 4、换货 1（第 2.5 节
 * 「售后」），再乘渠道与类目的倍率。
 */
export const AFTER_SALE_RATES: Record<
  'REFUND_ONLY' | 'RETURN_REFUND' | 'EXCHANGE',
  number
> = {
  REFUND_ONLY: 3.5,
  RETURN_REFUND: 4,
  EXCHANGE: 1,
};

/** 各类售后的理由权重；床品的退货以尺寸或颜色不符为主（见生成器）。 */
export const AFTER_SALE_REASON_WEIGHTS: Record<
  'REFUND_ONLY' | 'RETURN_REFUND' | 'EXCHANGE',
  Partial<Record<AfterSaleReason, number>>
> = {
  REFUND_ONLY: {
    MISSING_OR_WRONG: 40,
    DAMAGED_IN_TRANSIT: 35,
    QUALITY_ISSUE: 25,
  },
  RETURN_REFUND: {
    NO_REASON_7_DAYS: 45,
    SIZE_COLOR_MISMATCH: 25,
    QUALITY_ISSUE: 25,
    DAMAGED_IN_TRANSIT: 5,
  },
  EXCHANGE: { SIZE_COLOR_MISMATCH: 70, QUALITY_ISSUE: 30 },
};

export const INVOICE_TYPES = [
  { id: 'NONE', name: '不开' },
  { id: 'E_NORMAL', name: '电子普票' },
  { id: 'VAT_SPECIAL', name: '增值税专票' },
] as const;
export type InvoiceType = (typeof INVOICE_TYPES)[number]['id'];

export const ORDER_TAGS = [
  { id: 'PRESALE', name: '预售' },
  { id: 'URGENT', name: '加急' },
  { id: 'GIFT', name: '礼品' },
  { id: 'RISK_REVIEW', name: '风控复核' },
] as const;
export type OrderTag = (typeof ORDER_TAGS)[number]['id'];

/** 买家留言的素材；生成器用 faker 拼出一池留言，订单从池里取。 */
export const REMARKS = [
  '请尽快发货，谢谢',
  '麻烦改地址，搬家了',
  '能改地址到公司吗？',
  '送人的，别放价签和发票',
  '周末再送，工作日家里没人',
  '麻烦多包一层泡沫，上次磕坏了',
  '颜色要和上次买的一样',
  '加急，周五前要用',
  '放门口快递柜就行',
  '不要放小票',
  '请开发票，抬头见订单',
  '和另一单一起发',
] as const;

export const REMARK_SIGNATURES = ['先生', '女士', '小姐'] as const;

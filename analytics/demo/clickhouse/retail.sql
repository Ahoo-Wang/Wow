-- The demo's analyst wide table: two years of a retailer's order lines up to today, one row per line, made up in
-- place so the demo needs no download. The numbers are deterministic for a day: every row is a hash of its number.
-- GMV grows about 15% a year, peaks on 11.11 and 6.18 and on weekends, and differs by city, channel and category, so
-- the period comparisons, the shares and the rankings have something to say.

CREATE DATABASE IF NOT EXISTS retail;

CREATE TABLE IF NOT EXISTS retail.order_lines
(
    id            String,
    tenant_id     String,
    order_id      String,
    order_date    Date,
    order_time    DateTime('Asia/Shanghai'),
    region        String,
    province      String,
    city          String,
    channel       String,
    category      String,
    brand         String,
    customer_type String,
    member_phone  String,
    quantity      UInt32,
    gmv           Decimal(18, 2),
    cost          Decimal(18, 2),
    discount      Decimal(18, 2),
    refunded      Bool
)
ENGINE = MergeTree
ORDER BY (tenant_id, order_date, id);

INSERT INTO retail.order_lines
WITH
    -- (city, province, region, weight of the city's sales)
    [
        ('上海', '上海', 'east', 10), ('南京', '江苏', 'east', 5), ('苏州', '江苏', 'east', 6),
        ('无锡', '江苏', 'east', 3), ('杭州', '浙江', 'east', 7), ('宁波', '浙江', 'east', 4),
        ('温州', '浙江', 'east', 2), ('北京', '北京', 'north', 9), ('石家庄', '河北', 'north', 2),
        ('保定', '河北', 'north', 1), ('济南', '山东', 'north', 3), ('青岛', '山东', 'north', 4),
        ('烟台', '山东', 'north', 2), ('广州', '广东', 'south', 8), ('深圳', '广东', 'south', 9),
        ('佛山', '广东', 'south', 3), ('东莞', '广东', 'south', 3), ('福州', '福建', 'south', 2),
        ('厦门', '福建', 'south', 3), ('成都', '四川', 'west', 6), ('绵阳', '四川', 'west', 1),
        ('重庆', '重庆', 'west', 5), ('西安', '陕西', 'west', 4)
    ] AS cities,
    arrayFlatten(arrayMap((c, i) -> arrayResize([i], c.4, i), cities, arrayEnumerate(cities))) AS cityPicks,
    -- (category, brands, unit price)
    [
        ('数码', ['华为', '小米', 'Apple'], 2400), ('家电', ['美的', '格力', '海尔'], 1800),
        ('服饰', ['优衣库', '李宁', '安踏'], 260), ('美妆', ['欧莱雅', '雅诗兰黛', '珀莱雅'], 320),
        ('食品', ['三只松鼠', '良品铺子', '百草味'], 80), ('母婴', ['飞鹤', '好奇', '帮宝适'], 210)
    ] AS categories,
    ['online', 'online', 'online', 'store', 'store', 'mini_program'] AS channels
SELECT
    concat('L', leftPad(toString(number), 8, '0'))                         AS id,
    'demo'                                                                 AS tenant_id,
    concat('O', toString(day), '-', toString(slot))                         AS order_id,
    today() - toIntervalDay(day)                                            AS order_date,
    toDateTime(order_date, 'Asia/Shanghai') + toIntervalSecond(28800 + cityHash64(day, slot, 'time') % 50400)
                                                                           AS order_time,
    cities[cityPicks[1 + cityHash64(day, slot, 'city') % length(cityPicks)]].3 AS region,
    cities[cityPicks[1 + cityHash64(day, slot, 'city') % length(cityPicks)]].2 AS province,
    cities[cityPicks[1 + cityHash64(day, slot, 'city') % length(cityPicks)]].1 AS city,
    channels[1 + cityHash64(day, slot, 'channel') % length(channels)]         AS channel,
    categories[1 + cityHash64(number, 'category') % length(categories)].1  AS category,
    arrayElement(categories[1 + cityHash64(number, 'category') % length(categories)].2,
        1 + cityHash64(number, 'brand') % 3)                               AS brand,
    if(cityHash64(day, slot, 'new') % 10 < 3, 'new', 'returning')            AS customer_type,
    concat('1', toString(30 + cityHash64(day, slot, 'phone') % 60),
        leftPad(toString(cityHash64(day, slot, 'tail') % 100000000), 8, '0'))   AS member_phone,
    toUInt32(1 + cityHash64(number, 'qty') % 3)                            AS quantity,
    toDecimal64(
        categories[1 + cityHash64(number, 'category') % length(categories)].3 * quantity
        -- 15% a year of growth, counted back from today
        * (1 - 0.15 * day / 365)
        -- 11.11 and 6.18, and the weekend
        * multiIf(toMonth(order_date) = 11 AND toDayOfMonth(order_date) BETWEEN 1 AND 11, 2.2,
                  toMonth(order_date) = 6 AND toDayOfMonth(order_date) BETWEEN 1 AND 18, 1.6,
                  toDayOfWeek(order_date) >= 6, 1.25, 1)
        * (0.7 + (cityHash64(number, 'price') % 60) / 100), 2)            AS gmv,
    toDecimal64(gmv * (0.55 + (cityHash64(number, 'cost') % 25) / 100), 2) AS cost,
    toDecimal64(if(channel = 'online', gmv * 0.05, 0), 2)                  AS discount,
    cityHash64(number, 'refund') % 100 < 4                                 AS refunded
-- An order is two lines of the same day: lines n and n + 730 share the day and the slot.
FROM (SELECT number, number % 730 AS day, intDiv(number, 1460) AS slot FROM numbers(200000));

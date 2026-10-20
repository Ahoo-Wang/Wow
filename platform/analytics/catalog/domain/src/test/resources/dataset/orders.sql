CREATE OR REPLACE VIEW `wow_datasets`.`ds_shop_orders_v3` AS
SELECT
    `o`.`tenant` AS `tenant_id`,
    `o`.`owner` AS `owner_id`,
    `o`.`member_id` AS `order_id`,
    `o`.`phone` AS `phone`,
    JSONExtractString(`m`.`state`, 'customer', 'phone') AS `member_phone`,
    `r`.`name` AS `region`,
    `o`.`amount` AS `amount`,
    toStartOfMonth(`o`.`placed_at`) AS `month`
FROM `analytics`.`orders` AS `o`
LEFT JOIN (SELECT * FROM `analytics`.`shop_member_state` FINAL) AS `m` ON `o`.`tenant` = `m`.`tenant_id` AND `o`.`member_id` = `m`.`id`
INNER JOIN `analytics`.`regions` AS `r` ON `o`.`region` = `r`.`code`
WHERE ((`o`.`amount` > 0))
SETTINGS join_use_nulls = 1

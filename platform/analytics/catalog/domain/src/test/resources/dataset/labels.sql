CREATE OR REPLACE VIEW `wow_datasets`.`ds_shop_labels_v1` AS
SELECT
    `o`.`tenant` AS `tenant_id`,
    `o`.`owner` AS `owner_id`,
    concat(`o`.`region`, ' \\ it\'s `x`', toString((-(`o`.`amount`) % 3))) AS `label`,
    toString(if(((`o`.`amount` >= 100) AND NOT (`o`.`phone` IS NULL)), true, false)) AS `big`,
    multiIf(`o`.`region` IN ('n', 's'), 1, `o`.`region` NOT IN ('e'), 2.5, NULL) AS `kind`
FROM `analytics`.`orders` AS `o`
WHERE ((`o`.`region` IS NOT NULL OR ((`o`.`amount` / 2) < 10))) AND ((`o`.`phone` != ''))

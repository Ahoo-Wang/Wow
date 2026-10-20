CREATE OR REPLACE VIEW `bi`.`ds_ops_regions_v1` AS
SELECT
    `r`.`code` AS `code`,
    `r`.`name` AS `name`
FROM `analytics`.`regions` AS `r`

# Snapshot Elements aggregation benchmark

执行日期：2026-08-22

## 环境与协议

- Apple M4 Pro（arm64），24 GiB 内存，Docker 29.7.2，Zulu OpenJDK 17.0.7。
- MongoDB 6.0.6。项目默认的 MongoDB 8.3.4 在当前 Docker Linux 6.19 内核上无法启动，因此本次通过环境变量覆盖镜像；未修改项目默认配置。
- Elasticsearch 9.2.6。
- 每个场景固定写入 10,000 个快照；单层和三层场景均为每快照 100 个叶子元素。
- JMH 1.37，单线程，1 个 fork，1 次 10 秒预热，3 次 10 秒测量，结果单位为 `ms/op`。
- group-key 排序在达到 `limit=10,000` 后停止；metric Top-N 完整遍历 bucket 并维护精确 Top-100。

## 结果

| 场景 | MongoDB | Elasticsearch |
| --- | ---: | ---: |
| root | 10.25 | 0.98 |
| single-low | 393.18 | 1.05 |
| single-high | 1,392.40 | 7.01 |
| three-low | 590.41 | 1.45 |
| three-high | 1,638.54 | 7.74 |
| single-metric | 1,613.15 | 1,788.22 |
| three-metric | 1,835.49 | 1,790.14 |

本报告是当前机器上的工程基线，不用于跨存储后端排名。原始结果见
[`snapshot-elements-mongo.json`](snapshot-elements-mongo.json)、
[`snapshot-elements-mongo.txt`](snapshot-elements-mongo.txt)、
[`snapshot-elements-elasticsearch.json`](snapshot-elements-elasticsearch.json) 和
[`snapshot-elements-elasticsearch.txt`](snapshot-elements-elasticsearch.txt)。

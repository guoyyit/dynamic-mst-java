# Dynamic MST Java

本地图数据服务：维护一张带权无向图的边集合，支持边的插入、删除、权重更新，
以及**最小生成森林（MSF）**查询。查询与变更可并发进行，服务可在崩溃重启后恢复到
最后一个已提交版本。

## 一致性语义（正确性基准）

- 任意变更序列之后的查询结果，与对**当前边集合从零全量重算**的结果**完全一致**。
- 为消除等权边的二义性，定义**规范最小生成树**：边按 `(weight, u, v)` 字典序排序后执行
  Kruskal 得到的唯一森林。动态维护算法在每次提交后都产出该规范结果（见
  `GraphState` 中按路径最大边替换 / 割最小边补入的增量维护）。
- 顶点用 `int` 表示，边无向（`{u,v}` 与 `{v,u}` 视为同一条边），权重为 `long`。

### 最小生成森林（图不连通时）

当图不连通时，查询返回的不是单一最小生成树，而是**覆盖每个连通分量的最小生成森林**：
每个连通分量各自选出最小生成树，所有这些树的并集即为结果。返回字段 `componentCount`
给出连通分量数；对含 `N` 个非孤立顶点、`C` 个连通分量的图，森林恰含 `N - C` 条边。
自环（`u == v`）按普通边存储，但永远不会进入任何生成树。

## 版本与并发

- 每次被接受的提交（单条或批量）生成一个**单调递增的新版本号**。
- 写操作通过单把写锁串行化，提交顺序确定（即锁获取顺序），在锁内完成
  “复制状态 → 逐条应用 → 写 WAL → 原子发布快照”。
- 查询**不加锁**，只读取 `volatile` 发布的不可变 `MstResult`。因此任一查询只能观察到
  某个完整已提交版本，不会读到混合多个版本的边集或批次的中间状态。

## 批量原子性

- `POST /api/graph/edges/batch` 在一次提交内原子应用整批变更：要么全部生效，要么全部不生效。
- 批量大小上限可配置（`graph.max-batch-size`），超限整批拒绝（HTTP 400），不留部分状态。
- 批次内对同一条边的多次操作按顺序在同一临时状态上计算，结果逐条返回。
- 结构性非法请求（缺 `type/u/v/weight`、空批次）在任何状态修改前被拒绝。

## 无效操作的确定性语义

每条操作返回 `APPLIED`（改变边集）或 `IGNORED`（不改变边集，附 `reason`）：

| 操作 | 情况 | 结果 |
| --- | --- | --- |
| `INSERT` | 边已存在 | `IGNORED` |
| `INSERT` | 边不存在 | `APPLIED`（插入） |
| `UPDATE_WEIGHT` | 边不存在 | `IGNORED` |
| `UPDATE_WEIGHT` | 新权重与旧权重相同 | `IGNORED` |
| `UPDATE_WEIGHT` | 存在且权重变化 | `APPLIED`（更新） |
| `UPSERT` | 边不存在 | `APPLIED`（插入） |
| `UPSERT` | 存在且权重相同 | `IGNORED`（幂等） |
| `UPSERT` | 存在且权重不同 | `APPLIED`（更新） |
| `DELETE` | 边不存在（含重复删除） | `IGNORED` |
| `DELETE` | 边存在 | `APPLIED`（删除） |

所有 `IGNORED` 操作都不会改变查询结果；但它们所处的提交仍会产生一个新版本号，
以保证提交顺序可观察。

## 崩溃恢复

持久化由预写日志（WAL）+ 周期快照组成（`PersistentStore`）：

- 每次提交先把整批变更作为一帧追加到 WAL，并 `fsync` 落盘后才对查询可见。
- 恢复时先载入最高版本快照，再顺序重放其后的 WAL 记录；末尾不完整或 CRC 校验失败的
  帧被视为崩溃时未落盘完整的部分提交，直接丢弃。
- 因此：已提交变更重启后必然生效，未提交变更必然消失，且每条已提交变更只应用一次
  （按版本号去重），不会重复或遗漏。
- 每 `graph.checkpoint-interval` 次提交写一次快照（临时文件 + 原子 rename 发布），
  随后压缩旧 WAL，使日志长度与恢复时间保持有界。

## 配置项

| 属性 | 环境变量 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `graph.data-dir` | `GRAPH_DATA_DIR` | `data/graph` | 快照与 WAL 目录 |
| `graph.max-batch-size` | `GRAPH_MAX_BATCH_SIZE` | `1000` | 单批最大变更条数 |
| `graph.checkpoint-interval` | `GRAPH_CHECKPOINT_INTERVAL` | `100` | 每隔多少次提交生成快照 |

## HTTP API

- `GET /api/graph/mst`：返回当前版本的最小生成森林。
  ```json
  {
    "version": 12,
    "totalWeight": 143,
    "edgeCount": 6,
    "componentCount": 2,
    "edges": [ {"u": 1, "v": 2, "weight": 3} ]
  }
  ```
- `POST /api/graph/edges`：提交单条变更。
  请求体：`{"type": "UPSERT|INSERT|UPDATE_WEIGHT|DELETE", "u": 1, "v": 2, "weight": 3}`
  （`DELETE` 省略 `weight`）。
- `POST /api/graph/edges/batch`：原子提交一批变更。
  请求体：`{"changes": [ {...}, {...} ]}`。
- 非法请求返回 HTTP 400 与错误信息。

## 性能与规模基准

动态更新为增量维护（连通路径 / 割的局部搜索，复杂度 `O(V+E)` 有界），
避免了每次变更 `O(E log E)` 的全量 Kruskal 重算。基准测试
（`ScaleBenchmarkTest`，默认跳过，`-Dbench=true` 启用）在
**十万条边、两万顶点**的稀疏图上实测：

| 指标 | 动态增量更新 | 每次全量 Kruskal |
| --- | --- | --- |
| 单操作延迟 | 约 9.2 ms | 约 6480 ms |
| 相对吞吐 | 基准 | 约 **700 倍**慢于动态方案 |

连续 2000 次提交期间测量到的堆增长约 182 KB（有界，仅与提交过程临时对象相关）。
随机变更序列的正确性通过与全量 Kruskal 重算逐条对照的自动化测试保证。

## 构建与测试

```bash
mvn test                 # 运行全部单元/集成测试（默认不含耗时基准）
mvn -Dtest=ScaleBenchmarkTest -Dbench=true test   # 运行十万边规模基准
mvn spring-boot:run      # 启动服务（默认 8080 端口）
```

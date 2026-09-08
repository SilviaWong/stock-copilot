# Stock Copilot 数据库设计说明书 (SQLite 版本)

本文档记录了 **Stock Copilot（股票/ETF 个人投资助手）** 的数据结构设计、存储规范及计算逻辑。

---

## 一、 数据库基本信息

* **数据库类型**：SQLite 3
* **文件存储路径**：`./data/stock.db`（存放在项目根目录下的 `data` 文件夹内）
* **建表脚本位置**：
  * 项目文档目录：[`doc/db/schema.sql`](./schema.sql)
  * 后端资源目录：`backend/src/main/resources/db/schema.sql`

---

## 二、 核心数据表结构

### 1. 交易流水表 (`transaction_record`)
> **定位**：核心事实表。按时间倒序记录用户的所有操作（买入、卖出、分红）。任何历史复盘与收益归因均由此表计算得出。

| 字段名 | 类型 | 允许为空 | 默认值 | 说明与取值范围 |
| :--- | :--- | :--- | :--- | :--- |
| `id` | INTEGER | 否 | 主键自增 | 唯一自增流水号 |
| `symbol` | VARCHAR(10) | 否 | - | 标的代码，如 `510300`, `600519`, `159915` |
| `name` | VARCHAR(50) | 否 | - | 标的名称，如 `沪深300ETF` |
| `action` | VARCHAR(10) | 否 | - | 操作类型：<br>• `BUY` (买入/建仓/加仓)<br>• `SELL` (卖出/减仓/清仓)<br>• `DIVIDEND` (现金分红) |
| `price` | DECIMAL(10, 4) | 否 | 0.0000 | 成交单价(元)，支持4位小数（满足低价ETF） |
| `quantity` | INTEGER | 否 | 0 | 成交数量(股/份)，A股整百买入，卖出支持散股 |
| `amount` | DECIMAL(12, 2) | 否 | 0.00 | 成交金额（不含税费），公式：`price * quantity` |
| `fee` | DECIMAL(10, 2) | 否 | 0.00 | 手续费与税费（佣金、过户费、印花税等） |
| `realized_pnl` | DECIMAL(12, 2) | 是 | 0.00 | **已实现盈亏**：当 `action=SELL` 时，计算该笔卖出扣除手续费后的真实净收益 |
| `trade_time` | TEXT | 否 | - | 成交时间，格式：`YYYY-MM-DD HH:MM:SS` |
| `strategy_tag` | VARCHAR(50) | 是 | NULL | 策略标签，如：`定投`、`网格加仓`、`突破追涨`、`情绪抄底`、`达到止盈`、`止损离场` |
| `notes` | TEXT | 是 | NULL | 买入/卖出理由记录（**核心复盘字段**，克服人性弱点） |
| `created_at` | TEXT | 否 | 当前时间 | 记录录入时间 |

---

### 2. 持仓汇总表 (`position`)
> **定位**：当前资产状态快照表。用于首页看板快速加载，每次录入或修改交易流水后，系统自动重算并更新此表。

| 字段名 | 类型 | 允许为空 | 默认值 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `id` | INTEGER | 否 | 主键自增 | 主键 |
| `symbol` | VARCHAR(10) | 否 | - | 标的代码（唯一索引 `UNIQUE`） |
| `name` | VARCHAR(50) | 否 | - | 标的名称 |
| `market` | VARCHAR(10) | 否 | 'SH' | 所属市场：`SH` (沪市), `SZ` (深市), `BJ` (北交所) |
| `hold_quantity` | INTEGER | 否 | 0 | 当前剩余持仓数量（为 0 表示已清仓） |
| `cost_price` | DECIMAL(10, 4) | 否 | 0.0000 | **持仓成本均价（保本价）** |
| `total_cost` | DECIMAL(12, 2) | 否 | 0.00 | 当前持仓总成本本金投入 |
| `target_take_profit` | DECIMAL(10, 4) | 是 | NULL | **目标止盈价**（达到该价位触发减仓/止盈建议） |
| `target_stop_loss` | DECIMAL(10, 4) | 是 | NULL | **目标止损价**（跌破该价位触发风控预警） |
| `updated_at` | TEXT | 否 | 当前时间 | 最后变动时间 |

---

### 3. 标的字典与自选关注表 (`stock_info`)
> **定位**：存储关注标的的基础信息与自选状态，输入代码时自动匹配名称与市场。

| 字段名 | 类型 | 允许为空 | 默认值 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `id` | INTEGER | 否 | 主键自增 | 主键 |
| `symbol` | VARCHAR(10) | 否 | - | 标的代码（唯一索引 `UNIQUE`） |
| `name` | VARCHAR(50) | 否 | - | 标的名称 |
| `market` | VARCHAR(10) | 否 | - | 所属市场：`SH`, `SZ`, `BJ` |
| `category` | VARCHAR(20) | 否 | 'ETF' | 分类：`ETF` (场内ETF基金), `STOCK` (个股) |
| `is_favorite` | INTEGER | 否 | 0 | 是否加入自选观察池：`1` 是, `0` 否 |
| `created_at` | TEXT | 否 | 当前时间 | 创建时间 |

---

## 三、 金融计算核心算法规范

### 1. 买入/加仓时成本均价重算（加权平均摊薄法）
* 原有持仓：数量 $Q_0$，成本均价 $P_0$，手续费计入成本；
* 本次买入：数量 $Q_1$，成交价 $P_1$，手续费 $Fee$；
* **新持仓总数**：$Q_{new} = Q_0 + Q_1$
* **新持仓总成本**：$Cost_{new} = (Q_0 \times P_0) + (Q_1 \times P_1) + Fee$
* **新成本均价**：$P_{new} = Cost_{new} / Q_{new}$

### 2. 卖出/减仓时已实现收益计算
* 当前持仓均价为 $P_{cost}$；
* 本次卖出：数量 $Q_{sell}$，成交单价 $P_{sell}$，手续费 $Fee_{sell}$；
* **已实现净利润**：
  $$\text{Realized PnL} = (P_{sell} - P_{cost}) \times Q_{sell} - Fee_{sell}$$
* 剩余持仓 $Q_{remain} = Q_{old} - Q_{sell}$，**成本均价保持不变**，总成本对应缩减。

### 3. 浮动盈亏（结合实时行情）
* 腾讯/新浪接口拉取当前实时价 $P_{current}$；
* **浮动盈亏额**：$(\text{实时价} - \text{成本均价}) \times \text{当前持仓数量}$
* **浮动盈亏率**：$\frac{\text{实时价} - \text{成本均价}}{\text{成本均价}} \times 100\%$

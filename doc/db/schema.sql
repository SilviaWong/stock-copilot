-- ==============================================================================
-- 股票/ETF 个人投资助手 (Stock Copilot) - SQLite 数据库建表脚本
-- 数据库类型: SQLite 3
-- 默认存储文件建议: ./data/stock.db
-- ==============================================================================

-- 1. 标的字典与自选表 (stock_info)
CREATE TABLE IF NOT EXISTS stock_info (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol VARCHAR(10) NOT NULL UNIQUE,     -- 标的代码，如 510300, 600519
    name VARCHAR(50) NOT NULL,              -- 标的名称，如 沪深300ETF
    market VARCHAR(10) NOT NULL,            -- 所属市场: SH(上海), SZ(深圳), BJ(北京)
    category VARCHAR(20) NOT NULL DEFAULT 'ETF', -- 类型: STOCK(股票), ETF(场内ETF)
    is_favorite INTEGER NOT NULL DEFAULT 0, -- 是否自选关注: 1是, 0否
    created_at TEXT DEFAULT (datetime('now', 'localtime')) -- 创建时间
);

CREATE INDEX IF NOT EXISTS idx_stock_info_symbol ON stock_info(symbol);


-- 2. 当前持仓汇总表 (position)
-- 说明: 汇总当前持有标的、剩余股数、成本均价及预警止盈止损线
CREATE TABLE IF NOT EXISTS position (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol VARCHAR(10) NOT NULL UNIQUE,     -- 标的代码(唯一)
    name VARCHAR(50) NOT NULL,              -- 标的名称
    market VARCHAR(10) NOT NULL DEFAULT 'SH', -- 所属市场: SH, SZ, BJ
    hold_quantity INTEGER NOT NULL DEFAULT 0, -- 当前持仓股数(为0代表已清仓)
    cost_price DECIMAL(10, 4) NOT NULL DEFAULT 0.0000, -- 持仓成本均价(保本价，元)
    total_cost DECIMAL(12, 2) NOT NULL DEFAULT 0.00,   -- 持仓总投入成本(元)
    target_take_profit DECIMAL(10, 4) DEFAULT NULL,    -- 目标止盈价(元，辅助决策)
    target_stop_loss DECIMAL(10, 4) DEFAULT NULL,      -- 目标止损价(元，风控预警)
    updated_at TEXT DEFAULT (datetime('now', 'localtime')) -- 最后更新时间
);

CREATE INDEX IF NOT EXISTS idx_position_symbol ON position(symbol);


-- 3. 交易流水事实表 (transaction_record)
-- 说明: 核心事实流水，每一笔买入、卖出、分红、手续费
CREATE TABLE IF NOT EXISTS transaction_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol VARCHAR(10) NOT NULL,            -- 标的代码
    name VARCHAR(50) NOT NULL,              -- 标的名称
    action VARCHAR(10) NOT NULL,            -- 动作: BUY(买入/加仓), SELL(卖出/减仓), DIVIDEND(分红)
    price DECIMAL(10, 4) NOT NULL DEFAULT 0.0000, -- 成交单价(元)
    quantity INTEGER NOT NULL DEFAULT 0,    -- 成交数量(股/份)
    amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00,  -- 成交总金额(不含手续费)
    fee DECIMAL(10, 2) NOT NULL DEFAULT 0.00,     -- 交易税费(佣金+印花税+过户费等)
    realized_pnl DECIMAL(12, 2) DEFAULT 0.00,    -- 已实现盈亏(仅SELL卖出时计算净赚/净亏)
    trade_time TEXT NOT NULL,               -- 成交时间，格式: YYYY-MM-DD HH:MM:SS
    strategy_tag VARCHAR(50) DEFAULT NULL,  -- 策略标签: 如 定投加仓、网格交易、均线突破、恐慌割肉等
    notes TEXT DEFAULT NULL,                -- 操作心得/复盘记录
    created_at TEXT DEFAULT (datetime('now', 'localtime')) -- 记录创建时间
);

CREATE INDEX IF NOT EXISTS idx_trans_symbol ON transaction_record(symbol);
CREATE INDEX IF NOT EXISTS idx_trans_time ON transaction_record(trade_time);


-- ==============================================================================
-- 初始化演示测试数据 (常用指数 ETF)
-- ==============================================================================
INSERT OR IGNORE INTO stock_info (symbol, name, market, category, is_favorite) 
VALUES ('510300', '沪深300ETF', 'SH', 'ETF', 1);

INSERT OR IGNORE INTO stock_info (symbol, name, market, category, is_favorite) 
VALUES ('159915', '创业板ETF', 'SZ', 'ETF', 1);

INSERT OR IGNORE INTO stock_info (symbol, name, market, category, is_favorite) 
VALUES ('510500', '中证500ETF', 'SH', 'ETF', 0);

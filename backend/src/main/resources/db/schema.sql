-- ==============================================================================
-- 股票/ETF 个人投资助手 (Stock Copilot) - SQLite 数据库建表脚本
-- 数据库类型: SQLite 3
-- 默认存储文件建议: ./data/stock.db
-- ==============================================================================

-- 1. 标的字典与自选表 (stock_info)
CREATE TABLE IF NOT EXISTS stock_info (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol VARCHAR(10) NOT NULL UNIQUE,
    name VARCHAR(50) NOT NULL,
    market VARCHAR(10) NOT NULL,
    category VARCHAR(20) NOT NULL DEFAULT 'ETF',
    is_favorite INTEGER NOT NULL DEFAULT 0,
    created_at TEXT DEFAULT (datetime('now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_stock_info_symbol ON stock_info(symbol);


-- 2. 当前持仓汇总表 (position)
CREATE TABLE IF NOT EXISTS position (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol VARCHAR(10) NOT NULL UNIQUE,
    name VARCHAR(50) NOT NULL,
    market VARCHAR(10) NOT NULL DEFAULT 'SH',
    hold_quantity INTEGER NOT NULL DEFAULT 0,
    cost_price DECIMAL(10, 4) NOT NULL DEFAULT 0.0000,
    total_cost DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    target_take_profit DECIMAL(10, 4) DEFAULT NULL,
    target_stop_loss DECIMAL(10, 4) DEFAULT NULL,
    updated_at TEXT DEFAULT (datetime('now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_position_symbol ON position(symbol);


-- 3. 交易流水事实表 (transaction_record)
CREATE TABLE IF NOT EXISTS transaction_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol VARCHAR(10) NOT NULL,
    name VARCHAR(50) NOT NULL,
    action VARCHAR(10) NOT NULL,
    price DECIMAL(10, 4) NOT NULL DEFAULT 0.0000,
    quantity INTEGER NOT NULL DEFAULT 0,
    amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    fee DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    realized_pnl DECIMAL(12, 2) DEFAULT 0.00,
    trade_time TEXT NOT NULL,
    strategy_tag VARCHAR(50) DEFAULT NULL,
    notes TEXT DEFAULT NULL,
    created_at TEXT DEFAULT (datetime('now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_trans_symbol ON transaction_record(symbol);
CREATE INDEX IF NOT EXISTS idx_trans_time ON transaction_record(trade_time);


-- 初始化基础常用标的
INSERT OR IGNORE INTO stock_info (symbol, name, market, category, is_favorite) 
VALUES ('510300', '沪深300ETF', 'SH', 'ETF', 1);

INSERT OR IGNORE INTO stock_info (symbol, name, market, category, is_favorite) 
VALUES ('159915', '创业板ETF', 'SZ', 'ETF', 1);

INSERT OR IGNORE INTO stock_info (symbol, name, market, category, is_favorite) 
VALUES ('510500', '中证500ETF', 'SH', 'ETF', 0);

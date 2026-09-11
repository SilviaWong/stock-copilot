package com.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 前端持仓展示对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PositionVO {
    private Long id;
    private String symbol;
    private String name;
    private String market;
    private Integer holdQuantity;
    private BigDecimal costPrice;
    private BigDecimal totalCost;
    private BigDecimal targetTakeProfit;
    private BigDecimal targetStopLoss;
    private String updatedAt;

    // 动态行情与收益衍生指标 (接入行情后实时计算)
    private BigDecimal currentPrice;       // 实时现价
    private BigDecimal changePercent;      // 今日涨跌幅 (%)
    private BigDecimal dailyPnl;           // 今日持仓盈亏变动 (changeAmount * holdQuantity)
    private BigDecimal marketValue;        // 当前市值 (currentPrice * holdQuantity)
    private BigDecimal floatingPnl;        // 浮动盈亏额 (marketValue - totalCost)
    private BigDecimal floatingPnlRate;    // 浮动盈亏率 (%)
    private Boolean takeProfitAlert;       // 是否达到止盈价
    private Boolean stopLossAlert;         // 是否跌破止损价

    // 券商口径指标 (做T盈亏摊薄保本法)
    private BigDecimal dilutedCostPrice;   // 摊薄成本价 (保本价 = 摊薄总成本 / 持仓股数)
    private BigDecimal dilutedTotalCost;   // 摊薄总成本 (累计买入 - 累计卖出/分红 + 手续费)
    private BigDecimal totalPnl;           // 标的历史累计总盈亏 (市值 - 摊薄总成本 = 浮盈 + 已落袋)
    private BigDecimal totalPnlRate;       // 标的累计总收益率 (%)

    // 投资与做T操作决策推荐信号
    private TradeSignalVO tradeSignal;

    // 相对大盘表现与市场共振 (Relative Strength / Alpha)
    private String benchmarkSymbol;            // 对应基准指数代码 (如 sz399006)
    private String benchmarkName;              // 对应基准指数名称 (如 创业板指)
    private BigDecimal benchmarkChangePercent; // 基准指数今日涨跌幅 (%)
    private BigDecimal relativeStrength;       // 相对强弱 Alpha (个股涨跌 - 基准涨跌)
    private String relativeStrengthStatus;     // 相对强弱评价 (例如: 🚀 强势领涨 / 🟢 偏强共振 / ⚪ 同步大盘 / 🟡 偏弱滞涨 / 🔴 逆势走弱)
    private String relativeStrengthLevel;      // 标签色彩等级 (success / primary / info / warning / danger)
}


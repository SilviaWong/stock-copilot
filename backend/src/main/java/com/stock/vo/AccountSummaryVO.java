package com.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 账户核心资产总览
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountSummaryVO {
    /**
     * 当前持仓总投入本金 (元)
     */
    private BigDecimal totalHoldCost;

    /**
     * 累计已落袋收益 / 已实现净盈亏 (元)
     */
    private BigDecimal totalRealizedPnl;

    /**
     * 历史总交易流水记录数
     */
    private Integer totalTrades;

    /**
     * 已清仓或减仓的卖出交易笔数
     */
    private Integer totalSells;

    /**
     * 盈利卖出的笔数
     */
    private Integer profitableSells;

    /**
     * 胜率 (盈利笔数 / 总卖出笔数 * 100%)
     */
    private BigDecimal winRate;

    /**
     * 当前正在持有的标的数量
     */
    private Integer holdingCount;

    /**
     * 当前持仓总市值 (元)
     */
    private BigDecimal totalMarketValue;

    /**
     * 当前持仓总浮动盈亏 (元，市值 - 本金)
     */
    private BigDecimal totalFloatingPnl;

    /**
     * 当前持仓总浮动盈亏率 (%)
     */
    private BigDecimal totalFloatingPnlRate;

    /**
     * 今日持仓总浮动盈亏变动 (元)
     */
    private BigDecimal totalDailyPnl;
}

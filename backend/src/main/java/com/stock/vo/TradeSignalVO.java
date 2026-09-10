package com.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 标的交易与做T决策推荐信号
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeSignalVO {

    /**
     * 信号类型: BUY (建议加仓/做T低吸), SELL (建议减仓/做T高抛), HOLD (观望持有), ALERT (止损/风险预警)
     */
    private String signalType;

    /**
     * 标签标题: 建议逢低加仓, 建议减仓做T, 持股观望, 触及止盈, 跌破止损等
     */
    private String title;

    /**
     * 建议操作详细说明与理由
     */
    private String description;

    /**
     * 建议参考价 (元)
     */
    private BigDecimal suggestedPrice;

    /**
     * 建议操作股数/份额
     */
    private Integer suggestedQuantity;

    /**
     * 加仓后预计摊薄成本价 (元，仅加仓信号有效)
     */
    private BigDecimal estimatedNewCost;

    /**
     * 卖出后预计落袋收益 (元，仅减仓信号有效)
     */
    private BigDecimal estimatedProfit;

    /**
     * UI 展现级别: success (绿色), warning (橙色), danger (红色), info (蓝色/灰色)
     */
    private String level;
}

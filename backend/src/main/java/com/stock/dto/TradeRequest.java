package com.stock.dto;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 交易流水录入请求参数
 */
@Data
public class TradeRequest {
    /**
     * 标的代码，如 510300, 600519
     */
    private String symbol;

    /**
     * 标的名称，可选（若不填自动从数据库或标的信息匹配）
     */
    private String name;

    /**
     * 交易类型: BUY(买入/加仓), SELL(卖出/减仓), DIVIDEND(分红)
     */
    private String action;

    /**
     * 成交单价(元)
     */
    private BigDecimal price;

    /**
     * 成交数量(股/份)
     */
    private Integer quantity;

    /**
     * 交易税费(元，若为空默认自动按 0 处理)
     */
    private BigDecimal fee;

    /**
     * 实际交易时间，格式: yyyy-MM-dd HH:mm:ss（不填默认当前时间）
     */
    private String tradeTime;

    /**
     * 策略分类标签: 如 定投、网格加仓、均线突破、止盈卖出
     */
    private String strategyTag;

    /**
     * 买入/卖出理由与复盘心得
     */
    private String notes;
}

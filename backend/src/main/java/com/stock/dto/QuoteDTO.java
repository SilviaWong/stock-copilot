package com.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 实时行情数据传输对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuoteDTO {
    /**
     * 标的代码 (如 510300)
     */
    private String symbol;

    /**
     * 标的名称 (如 沪深300ETF)
     */
    private String name;

    /**
     * 所属市场 (SH, SZ, BJ)
     */
    private String market;

    /**
     * 当前最新价 (元)
     */
    private BigDecimal currentPrice;

    /**
     * 昨收价 (元)
     */
    private BigDecimal yesterdayClose;

    /**
     * 今日开盘价 (元)
     */
    private BigDecimal openPrice;

    /**
     * 今日最高价 (元)
     */
    private BigDecimal highPrice;

    /**
     * 今日最低价 (元)
     */
    private BigDecimal lowPrice;

    /**
     * 今日涨跌额 (元)
     */
    private BigDecimal changeAmount;

    /**
     * 今日涨跌幅 (%)
     */
    private BigDecimal changePercent;

    /**
     * 行情更新时间 (格式: yyyy-MM-dd HH:mm:ss)
     */
    private String updateTime;
}

package com.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 大盘核心指数行情传输对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexQuoteDTO {
    /**
     * 指数代码 (带市场前缀，如 sh000001, sz399006)
     */
    private String symbol;

    /**
     * 指数名称 (如 上证指数, 创业板指)
     */
    private String name;

    /**
     * 当前点位
     */
    private BigDecimal currentPoints;

    /**
     * 昨收点位
     */
    private BigDecimal yesterdayClose;

    /**
     * 今日涨跌点数
     */
    private BigDecimal changeAmount;

    /**
     * 今日涨跌幅 (%)
     */
    private BigDecimal changePercent;

    /**
     * 成交额 (亿元)
     */
    private BigDecimal turnoverAmount;

    /**
     * 成交量 (万手)
     */
    private BigDecimal volume;

    /**
     * 行情更新时间
     */
    private String updateTime;
}

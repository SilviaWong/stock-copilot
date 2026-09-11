package com.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 全市场宏观大盘全貌与情绪晴雨表传输对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketOverviewDTO {
    /**
     * 四大核心大盘指数 (上证指数、深证成指、创业板指、科创50)
     */
    private List<IndexQuoteDTO> indices;

    /**
     * 两市总成交额 (亿元)
     */
    private BigDecimal totalTurnover;

    /**
     * 上证成交额 (亿元)
     */
    private BigDecimal shTurnover;

    /**
     * 深证成交额 (亿元)
     */
    private BigDecimal szTurnover;

    /**
     * 两市量能状态标签 (例如: 放量活跃 / 温和放量 / 存量博弈 / 缩量冷清)
     */
    private String turnoverStatus;

    /**
     * 市场情绪综合温度评分 (0 ~ 100)
     */
    private Integer sentimentScore;

    /**
     * 市场情绪定调标识 (FEVER / BULLISH / NEUTRAL / BEARISH / PANIC)
     */
    private String sentimentLevel;

    /**
     * 市场情绪标题 (例如: 🟢 偏多温和 / 结构反弹)
     */
    private String sentimentTitle;

    /**
     * 市场环境操盘建议简述
     */
    private String sentimentDesc;

    /**
     * 数据更新时间
     */
    private String updateTime;
}

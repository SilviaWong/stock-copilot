package com.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 连续 5 日分时走势数据 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FiveDayChartDTO {
    private String symbol;
    private String name;
    private BigDecimal basePrice;         // 基准收盘价
    private Integer holdQuantity;
    private BigDecimal costPrice;         // 买入均价
    private BigDecimal dilutedCostPrice;  // 做T摊薄保本价

    // 实时最新行情指标 (供图表顶部概览看板直接使用，解决切换股票时数据残留问题)
    private BigDecimal latestPrice;
    private BigDecimal preClose;
    private BigDecimal changeAmount;
    private BigDecimal changePercent;
    private BigDecimal high;
    private BigDecimal low;
    private Long totalVolume;
    private BigDecimal totalAmount;

    // 历史买卖流水打点 (供 5 日分时走势图打点复盘)
    private List<TradeMarkerDTO> tradeMarkers;

    // 连续扁平序列 (用于 ECharts 一次性绘制连续跨日走势)
    private List<String> times;           // ["09-04 09:30", ...]
    private List<BigDecimal> prices;      // 连续分时价格
    private List<BigDecimal> avgPrices;   // 连续均价
    private List<Long> volumes;           // 连续成交量
    private List<Integer> splitIndexes;   // 每天开盘点的索引位置 (用于绘制垂直虚线分割)
    private List<String> dayLabels;       // 每天的日期文本 ("09-11")
    private List<String> fullDates;       // 每天的完整年月日 ("2026-09-11")，用于精确过滤跨年跨月实战打点
}

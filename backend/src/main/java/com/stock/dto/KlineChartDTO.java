package com.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 日 K 线数据 DTO (包含蜡烛图、成交量、MA均线与历史买卖打点)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KlineChartDTO {
    private String symbol;
    private String name;
    private Integer holdQuantity;
    private BigDecimal costPrice;         // 买入均价线
    private BigDecimal dilutedCostPrice;  // 做T摊薄保本线

    private List<String> dates;           // 日期序列 ["2025-01-02", ...]
    private List<List<BigDecimal>> values; // 对应 ECharts 规范: [开盘 open, 收盘 close, 最低 low, 最高 high]
    private List<Long> volumes;           // 对应成交量

    // 经典移动平均线系统
    private List<BigDecimal> ma5;
    private List<BigDecimal> ma10;
    private List<BigDecimal> ma20;
    private List<BigDecimal> ma60;

    // 标的历史买卖打点图钉 (Buy/Sell Markers)
    private List<TradeMarkerDTO> tradeMarkers;
}

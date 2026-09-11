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

    // 布林带指标系统 (BOLL: 20, 2)
    private List<BigDecimal> bollMid;
    private List<BigDecimal> bollUpper;
    private List<BigDecimal> bollLower;

    // 平滑异同移动平均线 (MACD: 12, 26, 9)
    private List<BigDecimal> macdDif;
    private List<BigDecimal> macdDea;
    private List<BigDecimal> macdBar;

    // 随机指标 (KDJ: 9, 3, 3)
    private List<BigDecimal> kdjK;
    private List<BigDecimal> kdjD;
    private List<BigDecimal> kdjJ;

    // 次日 Pivot Points 支撑阻力点位网格
    private PivotPointsDTO pivotPoints;
}

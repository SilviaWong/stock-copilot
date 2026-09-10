package com.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 1日分时图走势数据 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MinuteChartDTO {
    private String symbol;
    private String name;
    private String date;                  // yyyy-MM-dd
    private BigDecimal preClose;          // 昨日收盘价 (分时中轴)
    private BigDecimal latestPrice;       // 最新价
    private BigDecimal changeAmount;      // 涨跌额
    private BigDecimal changePercent;     // 涨跌幅 (%)
    private BigDecimal high;              // 今日最高
    private BigDecimal low;               // 今日最低
    private Long totalVolume;             // 今日总成交股数
    private BigDecimal totalAmount;       // 今日总金额

    // 持仓关联指标 (持仓生命线)
    private Integer holdQuantity;
    private BigDecimal costPrice;         // 买入均价
    private BigDecimal dilutedCostPrice;  // 做T摊薄保本价

    // 分时数据序列
    private List<String> times;           // ["09:30", "09:31", ...]
    private List<BigDecimal> prices;      // 分时价格 (白线/走势线)
    private List<BigDecimal> avgPrices;   // 分时成交均价 (黄线)
    private List<Long> volumes;           // 每分钟成交量
    private List<TradeMarkerDTO> tradeMarkers; // 当日买卖打点
}

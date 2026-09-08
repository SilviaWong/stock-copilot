package com.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 截图识别提取出的候选交易项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OcrTradeItem {
    private String symbol;
    private String name;
    private String action; // BUY 或 SELL
    private BigDecimal price;
    private Integer quantity;
    private BigDecimal amount;
    private BigDecimal fee;
    private String tradeTime;
    private String strategyTag;
    private String notes;
}

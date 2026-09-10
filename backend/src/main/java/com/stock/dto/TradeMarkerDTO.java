package com.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 历史买卖交易点图钉标记 DTO (用于打在 K 线或分时图上)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeMarkerDTO {
    private Long id;
    private String tradeDate;       // yyyy-MM-dd
    private String tradeTime;       // yyyy-MM-dd HH:mm:ss
    private String action;          // BUY, SELL, DIVIDEND
    private BigDecimal price;       // 成交单价
    private Integer quantity;       // 成交股数
    private BigDecimal amount;      // 成交金额
    private BigDecimal fee;         // 手续费
    private BigDecimal realizedPnl; // 卖出已实现盈利 (元)
    private String strategyTag;     // 策略标签 (如: 网格低吸, 止盈做T)
    private String label;           // "B" 或 "S"
}

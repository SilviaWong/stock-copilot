package com.stock.dto;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 直接录入或修改已有持仓请求 (用于已有底仓建档或就地修正持仓)
 */
@Data
public class PositionInitRequest {
    /**
     * 标的代码 (如 510300)
     */
    private String symbol;

    /**
     * 标的名称 (如 沪深300ETF)
     */
    private String name;

    /**
     * 当前持仓数量 (股/份)
     */
    private Integer holdQuantity;

    /**
     * 当前持仓成本均价 (保本单价，元)
     */
    private BigDecimal costPrice;

    /**
     * 目标止盈价 (可选)
     */
    private BigDecimal targetTakeProfit;

    /**
     * 目标止损价 (可选)
     */
    private BigDecimal targetStopLoss;

    /**
     * 建档备注 (如 "在招商证券持有已有底仓")
     */
    private String notes;
}

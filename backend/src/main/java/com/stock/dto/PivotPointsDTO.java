package com.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 次日 Pivot Points 轴心点系统与高低点网格预测 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PivotPointsDTO {

    /**
     * 标的代码
     */
    private String symbol;

    /**
     * 基准交易日 (通常为最近一个交易日)
     */
    private String baseDate;

    /**
     * 基准收盘价
     */
    private BigDecimal baseClose;

    /**
     * 多空分水岭 (Pivot Point: P = (High + Low + Close) / 3)
     */
    private BigDecimal pivot;

    /**
     * 第一阻力位 (R1 = 2P - Low，做T第一止盈减仓点)
     */
    private BigDecimal r1;

    /**
     * 第二阻力位 (R2 = P + (High - Low)，强大多头进攻极限压力位)
     */
    private BigDecimal r2;

    /**
     * 第一支撑位 (S1 = 2P - High，做T低吸回踩第一承接点)
     */
    private BigDecimal s1;

    /**
     * 第二支撑位 (S2 = P - (High - Low)，极端恐慌下探强支撑位)
     */
    private BigDecimal s2;

    /**
     * 多空趋势判断 ("偏多震荡" / "多头主升" / "中性盘整" / "空头回踩")
     */
    private String status;

    /**
     * 实战挂单与做T操作锦囊
     */
    private String actionTip;
}

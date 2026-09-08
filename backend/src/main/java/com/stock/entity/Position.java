package com.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;

/**
 * 当前持仓汇总实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("position")
public class Position {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 标的代码 (唯一)
     */
    private String symbol;

    /**
     * 标的名称
     */
    private String name;

    /**
     * 所属市场: SH, SZ, BJ
     */
    private String market;

    /**
     * 当前持仓股数/份额 (为0表示已清仓)
     */
    private Integer holdQuantity;

    /**
     * 持仓成本均价 (保本价，元)
     */
    private BigDecimal costPrice;

    /**
     * 持仓总成本 (元)
     */
    private BigDecimal totalCost;

    /**
     * 目标止盈价 (元)
     */
    private BigDecimal targetTakeProfit;

    /**
     * 目标止损价 (元)
     */
    private BigDecimal targetStopLoss;

    /**
     * 最后更新时间
     */
    private String updatedAt;
}

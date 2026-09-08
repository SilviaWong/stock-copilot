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
 * 交易流水事实记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("transaction_record")
public class TransactionRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 标的代码
     */
    private String symbol;

    /**
     * 标的名称
     */
    private String name;

    /**
     * 动作: BUY(买入/加仓), SELL(卖出/减仓), DIVIDEND(分红)
     */
    private String action;

    /**
     * 成交单价 (元)
     */
    private BigDecimal price;

    /**
     * 成交数量 (股/份)
     */
    private Integer quantity;

    /**
     * 成交总金额 (不含税费，price * quantity)
     */
    private BigDecimal amount;

    /**
     * 交易税费 (佣金+印花税+过户费等)
     */
    private BigDecimal fee;

    /**
     * 已实现盈亏 (仅SELL卖出时计算净赚/净亏)
     */
    private BigDecimal realizedPnl;

    /**
     * 成交时间，格式: YYYY-MM-DD HH:MM:SS
     */
    private String tradeTime;

    /**
     * 策略标签: 如 定投、网格加仓、均线突破、恐慌割肉等
     */
    private String strategyTag;

    /**
     * 操作复盘心得/买卖理由
     */
    private String notes;

    /**
     * 记录创建时间
     */
    private String createdAt;
}

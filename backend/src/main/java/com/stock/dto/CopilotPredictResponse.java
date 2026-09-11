package com.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * AI 次日走势推演响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopilotPredictResponse {

    private String symbol;
    private String name;

    /**
     * 明日多空定调 ("强烈看多" / "震荡偏多" / "窄幅震荡" / "震荡偏空" / "破位防守")
     */
    private String trendVerdict;

    /**
     * 多头预估胜率 (例如 68 代表 68%)
     */
    private Integer bullishProbability;

    /**
     * 用户真实持仓买入均价
     */
    private BigDecimal holdCostPrice;

    /**
     * 用户真实持仓摊薄保本价
     */
    private BigDecimal dilutedCostPrice;

    /**
     * 最新现价/收盘价
     */
    private BigDecimal currentPrice;

    /**
     * 当前持仓股数
     */
    private Integer holdQuantity;

    /**
     * 轴心点系统网格
     */
    private PivotPointsDTO pivotPoints;

    /**
     * AI 推演结构化详报 (Markdown 格式)
     */
    private String analysisReport;

    /**
     * 本次推演使用的模型
     */
    private String modelUsed;

    /**
     * 耗时 (毫秒)
     */
    private Long latencyMs;

    /**
     * 推演时间戳
     */
    private String timestamp;
}

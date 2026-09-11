package com.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 次日走势推演请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopilotPredictRequest {

    /**
     * 目标标的代码 (如 159242)
     */
    private String symbol;

    /**
     * AI 模型配置 (复用 OCR/设置配置)
     */
    private OcrConfigRequest config;
}

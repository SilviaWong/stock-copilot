package com.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * AI 投资副驾对话请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopilotChatRequest {

    /**
     * 用户提问内容
     */
    private String message;

    /**
     * 特定关注的标的代码 (可选，如 159242)
     */
    private String targetSymbol;

    /**
     * 历史多轮对话记录 (可选)
     */
    private List<Map<String, String>> history;

    /**
     * 用户自定义 AI 模型配置 (复用已有模型代理配置)
     */
    private OcrConfigRequest config;
}

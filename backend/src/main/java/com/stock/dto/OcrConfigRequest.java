package com.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 自定义代理大模型配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OcrConfigRequest {
    /**
     * 自定义代理的基础URL (如 https://api.openai.com/v1 或自己的中转地址)
     */
    private String baseUrl;

    /**
     * API Key (如 sk-xxxx)
     */
    private String apiKey;

    /**
     * 视觉模型名称 (如 gpt-4o-mini, qwen-vl-plus, claude-3-5-sonnet 等)
     */
    private String model;

    /**
     * 协议类型: OPENAI (默认), CLAUDE, GEMINI, OLLAMA, CUSTOM
     */
    private String protocol;

    /**
     * 自定义完整请求端点 (当 protocol 为 CUSTOM 或需要精确指定 URL 时生效)
     */
    private String customEndpoint;
}

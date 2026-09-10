package com.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 投资副驾对话响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopilotChatResponse {

    /**
     * AI 生成的专业建议与回答内容 (Markdown 格式)
     */
    private String reply;

    /**
     * 实际调用的模型名称
     */
    private String modelUsed;

    /**
     * 接口耗时 (毫秒)
     */
    private Long latencyMs;

    /**
     * 响应时间
     */
    private String timestamp;
}

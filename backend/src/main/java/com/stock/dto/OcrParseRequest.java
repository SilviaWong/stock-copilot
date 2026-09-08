package com.stock.dto;

import lombok.Data;

/**
 * 截图解析请求参数
 */
@Data
public class OcrParseRequest {
    /**
     * 图片 Base64 数据 (可带或不带 data:image/xxx;base64, 前缀)
     */
    private String imageBase64;

    /**
     * 自定义代理配置 (可选，若前端未提供则使用后端配置)
     */
    private OcrConfigRequest config;

    /**
     * 用户预选/指定的标的代码 (可选，如 510300。若截图为单只标的成交明细，自动补全此代码)
     */
    private String targetSymbol;

    /**
     * 用户预选/指定的标的名称 (可选，如 沪深300ETF)
     */
    private String targetName;
}

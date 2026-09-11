package com.stock.controller;

import com.stock.common.Result;
import com.stock.dto.*;
import com.stock.service.CopilotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * AI 投资副驾交互接口 (智能投顾 / 全盘诊断 / 实时做T建议 / 次日走势推演)
 */
@RestController
@RequestMapping("/api/copilot")
@CrossOrigin
public class CopilotController {

    @Autowired
    private CopilotService copilotService;

    /**
     * 与 AI 投资副驾交互对话
     */
    @PostMapping("/chat")
    public Result<CopilotChatResponse> chat(@RequestBody CopilotChatRequest request) {
        CopilotChatResponse response = copilotService.chatWithCopilot(request);
        return Result.success(response);
    }

    /**
     * 一键生成全盘持仓与风险健康诊断报告
     */
    @PostMapping("/diagnose")
    public Result<CopilotChatResponse> diagnose(@RequestBody(required = false) OcrConfigRequest config) {
        CopilotChatResponse response = copilotService.diagnoseAccount(config);
        return Result.success(response);
    }

    /**
     * AI 次日走势推演 (结合量化指标、Pivot Points 与持仓生命线)
     */
    @PostMapping("/predict")
    public Result<CopilotPredictResponse> predict(@RequestBody CopilotPredictRequest request) {
        CopilotPredictResponse response = copilotService.predictNextDayTrend(request);
        return Result.success(response);
    }
}

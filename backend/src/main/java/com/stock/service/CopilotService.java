package com.stock.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.common.BusinessException;
import com.stock.dto.CopilotChatRequest;
import com.stock.dto.CopilotChatResponse;
import com.stock.dto.OcrConfigRequest;
import com.stock.entity.TransactionRecord;
import com.stock.mapper.TransactionRecordMapper;
import com.stock.vo.AccountSummaryVO;
import com.stock.vo.PositionVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * AI 投资副驾核心服务 (结合真实持仓与实时行情的智能顾问)
 */
@Service
public class CopilotService {

    private static final Logger log = LoggerFactory.getLogger(CopilotService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private PositionService positionService;

    @Autowired
    private TransactionRecordMapper transactionRecordMapper;

    @Autowired
    private VisionOcrService visionOcrService;

    @Value("${stock.ai.base-url:https://api.openai.com/v1}")
    private String defaultBaseUrl;

    @Value("${stock.ai.api-key:}")
    private String defaultApiKey;

    @Value("${stock.ai.model:gpt-4o-mini}")
    private String defaultModel;

    private final HttpClient httpClient;

    public CopilotService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /**
     * 与 AI 投资副驾交互对话
     */
    public CopilotChatResponse chatWithCopilot(CopilotChatRequest request) {
        if (!StringUtils.hasText(request.getMessage())) {
            throw new BusinessException("提问内容不能为空！");
        }

        OcrConfigRequest config = request.getConfig();
        String protocol = (config != null && StringUtils.hasText(config.getProtocol()))
                ? config.getProtocol().trim().toUpperCase() : "OPENAI";
        String baseUrl = (config != null && StringUtils.hasText(config.getBaseUrl()))
                ? config.getBaseUrl().trim() : defaultBaseUrl;
        String apiKey = (config != null && StringUtils.hasText(config.getApiKey()))
                ? config.getApiKey().trim() : defaultApiKey;
        String model = (config != null && StringUtils.hasText(config.getModel()))
                ? config.getModel().trim() : defaultModel;
        String customEndpoint = config != null ? config.getCustomEndpoint() : null;

        if (!StringUtils.hasText(apiKey) && !"OLLAMA".equalsIgnoreCase(protocol)) {
            throw new BusinessException("未检测到 API Key！请点击右上角「📷 截图导入流水」或在设置中配置大模型 API Key。");
        }

        // 1. 组装真实账户全量资产、持仓与最新行情上下文
        String systemPrompt = buildSystemPrompt(request.getTargetSymbol());

        // 2. 解析请求目标地址
        String targetUrl = visionOcrService.resolveTargetUrl(protocol, baseUrl, customEndpoint, model);

        // 3. 构建 HTTP 请求
        HttpRequest httpRequest = buildChatHttpRequest(protocol, targetUrl, apiKey, model, systemPrompt, request.getMessage(), request.getHistory());

        long startTime = System.currentTimeMillis();
        try {
            log.info("【AI 投资副驾】调用大模型: protocol={}, url={}, model={}", protocol, targetUrl, model);
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - startTime;

            if (response.statusCode() != 200) {
                log.error("AI 投资副驾调用失败: status={}, body={}", response.statusCode(), response.body());
                String tip = visionOcrService.generateErrorDiagnosis(response.statusCode(), targetUrl, protocol);
                throw new BusinessException("大模型响应异常 (" + response.statusCode() + "): " + tip + "\n上游信息: " + response.body());
            }

            String replyText = extractTextByProtocol(protocol, response.body());

            return CopilotChatResponse.builder()
                    .reply(replyText)
                    .modelUsed(model)
                    .latencyMs(latency)
                    .timestamp(LocalDateTime.now().format(TIME_FORMATTER))
                    .build();

        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("AI 投资副驾异常: ", e);
            throw new BusinessException("AI 响应失败: " + e.getMessage());
        }
    }

    /**
     * 生成一键全盘持仓诊断报告
     */
    public CopilotChatResponse diagnoseAccount(OcrConfigRequest config) {
        CopilotChatRequest request = new CopilotChatRequest();
        request.setConfig(config);
        request.setMessage("请根据我目前的整体账户资产、持仓标的分布、做T胜率以及实时行情与建议信号，进行一次全面的账户健康体检与实战操盘诊断。重点指出：1. 仓位与风险分布；2. 哪些标的目前最适合做T或止盈；3. 接下来一周的建议操作策略。");
        return chatWithCopilot(request);
    }

    /**
     * 构建包含真实资产与持仓的专业投资顾问 System Prompt
     */
    private String buildSystemPrompt(String targetSymbol) {
        AccountSummaryVO summary = positionService.getAccountSummary();
        List<PositionVO> holdings = positionService.listPositions(false);
        List<TransactionRecord> recentTrades = transactionRecordMapper.selectList(
                new LambdaQueryWrapper<TransactionRecord>()
                        .orderByDesc(TransactionRecord::getTradeTime)
                        .orderByDesc(TransactionRecord::getId)
                        .last("LIMIT 15")
        );

        StringBuilder sb = new StringBuilder();
        sb.append("你是一名兼具严密风控意识和实战经验的专业投资副驾驶 (Stock Copilot AI 投资顾问)。\n");
        sb.append("你的职责是协助用户进行 A 股和场内 ETF 的买卖决策、波段做 T 与仓位风险控制。\n\n");

        sb.append("【用户当前真实账户资产总览】：\n");
        sb.append(String.format("- 当前持仓总市值: ¥%.2f\n", summary.getTotalMarketValue() != null ? summary.getTotalMarketValue() : BigDecimal.ZERO));
        sb.append(String.format("- 当前投入总本金: ¥%.2f\n", summary.getTotalHoldCost() != null ? summary.getTotalHoldCost() : BigDecimal.ZERO));
        sb.append(String.format("- 持仓浮动盈亏: ¥%.2f (盈亏率: %s%%)\n",
                summary.getTotalFloatingPnl() != null ? summary.getTotalFloatingPnl() : BigDecimal.ZERO,
                summary.getTotalFloatingPnlRate() != null ? summary.getTotalFloatingPnlRate() : "0.00"));
        sb.append(String.format("- 累计已落袋盈利: ¥%.2f\n", summary.getTotalRealizedPnl() != null ? summary.getTotalRealizedPnl() : BigDecimal.ZERO));
        sb.append(String.format("- 账户历史综合净利润 (含浮盈): ¥%.2f\n", summary.getTotalNetProfit() != null ? summary.getTotalNetProfit() : BigDecimal.ZERO));
        sb.append(String.format("- 历史做T实战胜率: %s%% (共减仓 %d 笔，盈利 %d 笔)\n\n",
                summary.getWinRate() != null ? summary.getWinRate() : "0.00",
                summary.getTotalSells() != null ? summary.getTotalSells() : 0,
                summary.getProfitableSells() != null ? summary.getProfitableSells() : 0));

        sb.append("【用户当前持仓明细与实时行情 (含系统计算信号)】：\n");
        if (holdings.isEmpty()) {
            sb.append("当前暂无持仓。\n");
        } else {
            for (PositionVO p : holdings) {
                sb.append(String.format("▶ 标的: %s (%s, %s)\n", p.getName(), p.getSymbol(), p.getMarket()));
                sb.append(String.format("  持仓股数: %d 股 | 实时现价: ¥%.3f (今日涨跌: %s%%)\n",
                        p.getHoldQuantity(),
                        p.getCurrentPrice() != null ? p.getCurrentPrice() : p.getCostPrice(),
                        p.getChangePercent() != null ? p.getChangePercent() : "0.00"));
                sb.append(String.format("  买入均价: ¥%.4f | 摊薄保本价: ¥%.3f\n",
                        p.getCostPrice(),
                        p.getDilutedCostPrice() != null ? p.getDilutedCostPrice() : p.getCostPrice()));
                sb.append(String.format("  持仓浮动盈亏: ¥%.2f (%s%%) | 累计总盈亏(含落袋): ¥%.2f (%s%%)\n",
                        p.getFloatingPnl() != null ? p.getFloatingPnl() : BigDecimal.ZERO,
                        p.getFloatingPnlRate() != null ? p.getFloatingPnlRate() : "0.00",
                        p.getTotalPnl() != null ? p.getTotalPnl() : BigDecimal.ZERO,
                        p.getTotalPnlRate() != null ? p.getTotalPnlRate() : "0.00"));
                if (p.getTargetTakeProfit() != null || p.getTargetStopLoss() != null) {
                    sb.append(String.format("  目标止盈价: %s | 目标止损价: %s\n",
                            p.getTargetTakeProfit() != null ? "¥" + p.getTargetTakeProfit() : "未设",
                            p.getTargetStopLoss() != null ? "¥" + p.getTargetStopLoss() : "未设"));
                }
                if (p.getTradeSignal() != null) {
                    sb.append(String.format("  【系统决策建议】: %s - %s\n",
                            p.getTradeSignal().getTitle(), p.getTradeSignal().getDescription()));
                }
                sb.append("\n");
            }
        }

        sb.append("【用户近期操作流水记录 (最近15笔)】：\n");
        if (recentTrades.isEmpty()) {
            sb.append("暂无交易记录。\n");
        } else {
            for (TransactionRecord r : recentTrades) {
                sb.append(String.format("- [%s] %s %s: 单价 ¥%.3f x %d 股 (金额 ¥%.2f)%s\n",
                        r.getTradeTime(), r.getAction(), r.getSymbol(),
                        r.getPrice(), r.getQuantity(), r.getAmount(),
                        ("SELL".equalsIgnoreCase(r.getAction()) && r.getRealizedPnl() != null ? " [落袋 ¥" + r.getRealizedPnl() + "]" : "")));
            }
        }

        if (StringUtils.hasText(targetSymbol)) {
            sb.append(String.format("\n用户当前特别关注标的: [%s]，请对该标的重点展开推演。\n", targetSymbol));
        }

        sb.append("\n【回答铁律与表达规范】：\n");
        sb.append("1. **严禁空泛套话**：必须紧密结合用户当前的真实持仓成本价、摊薄保本价、当前数量与最新行情作答；\n");
        sb.append("2. **结论先行**：第一句话明确给出操作倾向：【建议逢低补仓】/【建议高抛做T】/【观望持有】/【止损防守】；\n");
        sb.append("3. **明确具体点位与股数**：给出具体的参考挂单价格、建议买卖手数，并预估操作后对持仓均价的摊薄效果或预计落袋金额；\n");
        sb.append("4. **纪律与防守底线**：提醒防守安全垫底线（如利用摊薄保本价设定安全线）；\n");
        sb.append("5. **排版格式**：采用优雅规范的 GitHub Markdown 格式，适当使用小标题、列表和加粗，语言干练务实，像老练的操盘手一样给用户清晰指引。\n");

        return sb.toString();
    }

    /**
     * 组装 HTTP 请求体
     */
    private HttpRequest buildChatHttpRequest(String protocol, String targetUrl, String apiKey, String model,
                                            String systemPrompt, String userMessage, List<Map<String, String>> history) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .header("Content-Type", "application/json");

        if ("GEMINI".equalsIgnoreCase(protocol)) {
            builder.header("x-goog-api-key", apiKey);
            String geminiPayload = buildGeminiPayload(systemPrompt, userMessage, history);
            return builder.POST(HttpRequest.BodyPublishers.ofString(geminiPayload)).build();
        } else if ("CLAUDE".equalsIgnoreCase(protocol)) {
            builder.header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01");
            String claudePayload = buildClaudePayload(model, systemPrompt, userMessage, history);
            return builder.POST(HttpRequest.BodyPublishers.ofString(claudePayload)).build();
        } else {
            // OPENAI / OLLAMA / CUSTOM 兼容协议
            if (StringUtils.hasText(apiKey)) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            String openAiPayload = buildOpenAiPayload(model, systemPrompt, userMessage, history);
            return builder.POST(HttpRequest.BodyPublishers.ofString(openAiPayload)).build();
        }
    }

    private String buildOpenAiPayload(String model, String systemPrompt, String userMessage, List<Map<String, String>> history) {
        try {
            Map<String, Object> root = new HashMap<>();
            root.put("model", model);
            root.put("temperature", 0.3);
            root.put("max_tokens", 4096);

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));

            if (history != null && !history.isEmpty()) {
                for (Map<String, String> h : history) {
                    if (h.containsKey("role") && h.containsKey("content")) {
                        messages.add(Map.of("role", h.get("role"), "content", h.get("content")));
                    }
                }
            }

            messages.add(Map.of("role", "user", "content", userMessage));
            root.put("messages", messages);

            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new BusinessException("构建请求载荷失败: " + e.getMessage());
        }
    }

    private String buildGeminiPayload(String systemPrompt, String userMessage, List<Map<String, String>> history) {
        try {
            Map<String, Object> root = new HashMap<>();

            // System Instruction
            Map<String, Object> sysInstruction = new HashMap<>();
            sysInstruction.put("parts", List.of(Map.of("text", systemPrompt)));
            root.put("systemInstruction", sysInstruction);

            // Contents
            List<Map<String, Object>> contents = new ArrayList<>();
            if (history != null && !history.isEmpty()) {
                for (Map<String, String> h : history) {
                    String role = "assistant".equalsIgnoreCase(h.get("role")) ? "model" : "user";
                    contents.add(Map.of("role", role, "parts", List.of(Map.of("text", h.get("content")))));
                }
            }
            contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", userMessage))));
            root.put("contents", contents);

            root.put("generationConfig", Map.of("temperature", 0.3, "maxOutputTokens", 4096));

            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new BusinessException("构建 Gemini 载荷失败: " + e.getMessage());
        }
    }

    private String buildClaudePayload(String model, String systemPrompt, String userMessage, List<Map<String, String>> history) {
        try {
            Map<String, Object> root = new HashMap<>();
            root.put("model", model);
            root.put("system", systemPrompt);
            root.put("max_tokens", 4096);
            root.put("temperature", 0.3);

            List<Map<String, Object>> messages = new ArrayList<>();
            if (history != null && !history.isEmpty()) {
                for (Map<String, String> h : history) {
                    messages.add(Map.of("role", h.get("role"), "content", h.get("content")));
                }
            }
            messages.add(Map.of("role", "user", "content", userMessage));
            root.put("messages", messages);

            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new BusinessException("构建 Claude 载荷失败: " + e.getMessage());
        }
    }

    /**
     * 根据协议从上游响应提取生成的正文内容
     */
    private String extractTextByProtocol(String protocol, String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            String proto = protocol != null ? protocol.trim().toUpperCase() : "OPENAI";

            if ("GEMINI".equals(proto)) {
                JsonNode candidates = root.path("candidates");
                if (candidates.isArray() && !candidates.isEmpty()) {
                    JsonNode parts = candidates.get(0).path("content").path("parts");
                    if (parts.isArray() && !parts.isEmpty()) {
                        return parts.get(0).path("text").asText();
                    }
                }
            } else if ("CLAUDE".equals(proto)) {
                JsonNode contentArr = root.path("content");
                if (contentArr.isArray() && !contentArr.isEmpty()) {
                    return contentArr.get(0).path("text").asText();
                }
            } else if ("OLLAMA".equals(proto)) {
                JsonNode msg = root.path("message");
                if (!msg.isMissingNode() && msg.has("content")) {
                    return msg.path("content").asText();
                }
            }

            // OpenAI / 通用格式
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).path("message");
                if (!message.isMissingNode() && message.has("content")) {
                    return message.path("content").asText();
                }
            }

            // 兜底提取可能存在的 content 或 text 字段
            if (root.has("content")) {
                return root.path("content").asText();
            }
            if (root.has("text")) {
                return root.path("text").asText();
            }

            return responseBody;
        } catch (Exception e) {
            log.warn("解析大模型输出文本异常: {}", e.getMessage());
            return responseBody;
        }
    }
}

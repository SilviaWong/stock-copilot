package com.stock.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.common.BusinessException;
import com.stock.dto.*;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    @Autowired
    private QuoteService quoteService;

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

        MarketOverviewDTO marketOverview = quoteService.getMarketOverview();
        if (marketOverview != null) {
            sb.append("【今日全市场宏观大盘指数与情绪晴雨表】：\n");
            if (marketOverview.getIndices() != null) {
                for (IndexQuoteDTO idx : marketOverview.getIndices()) {
                    sb.append(String.format("- %s (%s): 点位 %s (涨跌: %s%%, 成交额: ¥%s 亿)\n",
                            idx.getName(), idx.getSymbol(), idx.getCurrentPoints(), idx.getChangePercent(), idx.getTurnoverAmount()));
                }
            }
            sb.append(String.format("- 两市总成交额: ¥%s 亿元 (%s)\n",
                    marketOverview.getTotalTurnover() != null ? marketOverview.getTotalTurnover() : "-",
                    marketOverview.getTurnoverStatus()));
            sb.append(String.format("- 全市场多空情绪综合温度: %d/100 (%s)\n",
                    marketOverview.getSentimentScore(), marketOverview.getSentimentTitle()));
            sb.append(String.format("- 宏观大盘实战指引: %s\n\n", marketOverview.getSentimentDesc()));
        }

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
                if (p.getBenchmarkName() != null && p.getRelativeStrength() != null) {
                    sb.append(String.format("  基准指数对照: %s (涨跌: %s%%) | 相对强弱 Alpha: %s%% (%s)\n",
                            p.getBenchmarkName(), p.getBenchmarkChangePercent(),
                            p.getRelativeStrength(), p.getRelativeStrengthStatus()));
                }

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

    /**
     * AI 次日走势推演 (结合量化指标系统、Pivot Points 网格与真实持仓生命线)
     */
    public CopilotPredictResponse predictNextDayTrend(CopilotPredictRequest request) {
        if (request == null || !StringUtils.hasText(request.getSymbol())) {
            throw new BusinessException("标的代码不能为空！");
        }
        String symbol = request.getSymbol().trim();

        // 1. 获取标的当前持仓信息 (若有)
        PositionVO pos = null;
        try {
            List<PositionVO> holdings = positionService.listPositions(false);
            if (holdings != null) {
                pos = holdings.stream()
                        .filter(p -> symbol.equalsIgnoreCase(p.getSymbol()))
                        .findFirst()
                        .orElse(null);
            }
        } catch (Exception e) {
            log.warn("获取持仓数据异常: {}", e.getMessage());
        }

        // 2. 获取日K线行情与量化指标 (BOLL, MACD, KDJ, Pivot Points)
        KlineChartDTO kline = null;
        try {
            kline = quoteService.fetchKlineChart(symbol);
        } catch (Exception e) {
            log.warn("获取日K线数据异常: {}", e.getMessage());
        }

        String name = (kline != null && StringUtils.hasText(kline.getName())) ? kline.getName()
                : ((pos != null && StringUtils.hasText(pos.getName())) ? pos.getName() : symbol);

        BigDecimal currentPrice = null;
        if (pos != null && pos.getCurrentPrice() != null) {
            currentPrice = pos.getCurrentPrice();
        } else if (kline != null && kline.getValues() != null && !kline.getValues().isEmpty()) {
            List<BigDecimal> lastCandle = kline.getValues().get(kline.getValues().size() - 1);
            if (lastCandle.size() >= 2) {
                currentPrice = lastCandle.get(1); // 收盘价
            }
        }

        PivotPointsDTO pivot = (kline != null) ? kline.getPivotPoints() : null;
        BigDecimal holdCost = (pos != null) ? pos.getCostPrice() : null;
        BigDecimal dilutedCost = (pos != null) ? pos.getDilutedCostPrice() : null;
        Integer holdQty = (pos != null && pos.getHoldQuantity() != null) ? pos.getHoldQuantity() : 0;

        // 3. 模型配置与调用
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

        boolean hasApiKey = StringUtils.hasText(apiKey) || "OLLAMA".equalsIgnoreCase(protocol);

        if (hasApiKey) {
            try {
                String systemPrompt = buildPredictSystemPrompt();
                String userPrompt = buildPredictUserPrompt(symbol, name, currentPrice, pos, kline, pivot);
                String targetUrl = visionOcrService.resolveTargetUrl(protocol, baseUrl, customEndpoint, model);
                HttpRequest httpRequest = buildChatHttpRequest(protocol, targetUrl, apiKey, model, systemPrompt, userPrompt, null);

                long startTime = System.currentTimeMillis();
                log.info("【AI 走势推演】请求大模型: protocol={}, url={}, model={}", protocol, targetUrl, model);
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                long latency = System.currentTimeMillis() - startTime;

                if (response.statusCode() == 200 && StringUtils.hasText(response.body())) {
                    String replyText = extractTextByProtocol(protocol, response.body());
                    return buildPredictResponseFromAi(symbol, name, currentPrice, holdCost, dilutedCost, holdQty, pivot, replyText, model, latency);
                } else {
                    log.warn("大模型响应异常 ({})，启用量化规则引擎降级推演: {}", response.statusCode(), response.body());
                }
            } catch (Exception ex) {
                log.warn("大模型调用推演异常，切换至规则引擎: {}", ex.getMessage());
            }
        }

        // 4. 兜底策略：专业量化规则引擎直接生成推演报告
        return buildRuleBasedPredictResponse(symbol, name, currentPrice, holdCost, dilutedCost, holdQty, kline, pivot, pos);
    }

    private String buildPredictSystemPrompt() {
        return "你是一名顶级量化对冲基金操盘手与高频交易策略专家 (Stock Copilot AI 预测引擎)。\n" +
                "你擅长结合经典技术面指标（MA均线组、BOLL布林带通道、MACD柱状动能、KDJ超买超卖）、次日 Pivot Points 轴心点网格，以及投资者的真实持仓生命线（买入均价、摊薄保本价、持仓股数），提供高胜率、严密风控的次日多空走势推演与实战做T策略。\n\n" +
                "【严格输出格式规范】：\n" +
                "必须在回答的最开头两行输出以下固定格式，方便系统解析：\n" +
                "【明日趋势定调】: <填入：强烈看多 / 震荡偏多 / 窄幅震荡 / 震荡偏空 / 破位防守>\n" +
                "【多头预估胜率】: <填入0-100的整数，例如 68>%\n\n" +
                "随后使用规范的 Markdown 格式输出推演详报，必须包含以下四大部分：\n" +
                "### 📌 一、次日多空基准定调与概率评估\n" +
                "（给出明确方向论断、预期波动区间，结合高低点说明多空分歧）\n\n" +
                "### 📊 二、核心技术指标与网格点位拆解\n" +
                "（剖析MA均线多空排列、BOLL通道开口与上下轨压力支撑、MACD快慢线与红绿柱动能、KDJ超买超卖状态、次日 Pivot 中轴与 R1/S1 关键位）\n\n" +
                "### 🎯 三、持仓生命线对比与实战做T锦囊\n" +
                "（重点：对比当前价与用户的买入均价、做T摊薄保本价！给出具体的低吸加仓挂单点、冲高做T减仓点以及保本出逃点）\n\n" +
                "### 🛡️ 四、次日极端突发走势应对预案\n" +
                "（针对大幅高开或低开破位给出一票否决式防守底线）\n";
    }

    private String buildPredictUserPrompt(String symbol, String name, BigDecimal currentPrice,
                                           PositionVO pos, KlineChartDTO kline, PivotPointsDTO pivot) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("标的资产: %s (%s)\n", name, symbol));
        sb.append(String.format("最新现价/收盘价: ¥%s\n\n", currentPrice != null ? currentPrice : "未知"));

        MarketOverviewDTO marketOverview = quoteService.getMarketOverview();
        if (marketOverview != null) {
            sb.append("【今日全市场宏观大盘指数与情绪晴雨表】：\n");
            if (marketOverview.getIndices() != null) {
                for (IndexQuoteDTO idx : marketOverview.getIndices()) {
                    sb.append(String.format("- %s: %s (涨跌: %s%%, 成交¥%s亿)\n",
                            idx.getName(), idx.getCurrentPoints(), idx.getChangePercent(), idx.getTurnoverAmount()));
                }
            }
            sb.append(String.format("- 两市总成交额: ¥%s 亿元 (%s)\n",
                    marketOverview.getTotalTurnover() != null ? marketOverview.getTotalTurnover() : "-",
                    marketOverview.getTurnoverStatus()));
            sb.append(String.format("- 市场情绪综合温度: %d/100 (%s)\n",
                    marketOverview.getSentimentScore(), marketOverview.getSentimentTitle()));
            sb.append(String.format("- 宏观大势背景指引: %s\n\n", marketOverview.getSentimentDesc()));
        }

        if (pos != null && pos.getBenchmarkName() != null && pos.getRelativeStrength() != null) {
            sb.append(String.format("【标的与大盘共振/相对强弱 (Alpha)】：\n- 归属基准: %s (今日涨跌: %s%%)\n- 相对强弱 Alpha: %s%% (%s)\n\n",
                    pos.getBenchmarkName(), pos.getBenchmarkChangePercent(), pos.getRelativeStrength(), pos.getRelativeStrengthStatus()));
        }

        sb.append("【用户真实持仓生命线】：\n");

        if (pos != null && pos.getHoldQuantity() != null && pos.getHoldQuantity() > 0) {
            sb.append(String.format("- 当前持仓股数: %d 股\n", pos.getHoldQuantity()));
            sb.append(String.format("- 买入均价: ¥%s\n", pos.getCostPrice()));
            sb.append(String.format("- 做T摊薄保本价: ¥%s\n", pos.getDilutedCostPrice() != null ? pos.getDilutedCostPrice() : pos.getCostPrice()));
            sb.append(String.format("- 浮动盈亏: ¥%s (%s%%)\n", pos.getFloatingPnl(), pos.getFloatingPnlRate()));
            sb.append(String.format("- 历史累计总盈亏: ¥%s (%s%%)\n\n", pos.getTotalPnl(), pos.getTotalPnlRate()));
        } else {
            sb.append("- 目前暂未持有该标的 (属于潜在建仓/关注池观察状态)\n\n");
        }

        if (pivot != null) {
            sb.append("【次日 Pivot Points 关键轴心点位网格】：\n");
            sb.append(String.format("- 多空分水岭 (P): ¥%s\n", pivot.getPivot()));
            sb.append(String.format("- 第一阻力位 (R1 - 高抛做T): ¥%s\n", pivot.getR1()));
            sb.append(String.format("- 第二阻力位 (R2 - 强势冲顶): ¥%s\n", pivot.getR2()));
            sb.append(String.format("- 第一支撑位 (S1 - 低吸买点): ¥%s\n", pivot.getS1()));
            sb.append(String.format("- 第二支撑位 (S2 - 恐慌地板): ¥%s\n\n", pivot.getS2()));
        }

        if (kline != null && kline.getDates() != null && !kline.getDates().isEmpty()) {
            int lastIdx = kline.getDates().size() - 1;
            sb.append("【最新技术指标数值 (截取最新收盘)】：\n");
            if (kline.getMa5() != null && kline.getMa5().size() > lastIdx) {
                sb.append(String.format("- 均线系统: MA5=¥%s, MA10=¥%s, MA20=¥%s, MA60=¥%s\n",
                        kline.getMa5().get(lastIdx),
                        kline.getMa10().size() > lastIdx ? kline.getMa10().get(lastIdx) : "-",
                        kline.getMa20().size() > lastIdx ? kline.getMa20().get(lastIdx) : "-",
                        kline.getMa60().size() > lastIdx ? kline.getMa60().get(lastIdx) : "-"));
            }
            if (kline.getBollMid() != null && kline.getBollMid().size() > lastIdx && kline.getBollMid().get(lastIdx) != null) {
                sb.append(String.format("- 布林带 BOLL: 上轨=¥%s, 中轨=¥%s, 下轨=¥%s\n",
                        kline.getBollUpper().get(lastIdx), kline.getBollMid().get(lastIdx), kline.getBollLower().get(lastIdx)));
            }
            if (kline.getMacdDif() != null && kline.getMacdDif().size() > lastIdx) {
                sb.append(String.format("- MACD: DIF=%s, DEA=%s, MACD柱=%s\n",
                        kline.getMacdDif().get(lastIdx), kline.getMacdDea().get(lastIdx), kline.getMacdBar().get(lastIdx)));
            }
            if (kline.getKdjK() != null && kline.getKdjK().size() > lastIdx) {
                sb.append(String.format("- KDJ: K=%s, D=%s, J=%s\n",
                        kline.getKdjK().get(lastIdx), kline.getKdjD().get(lastIdx), kline.getKdjJ().get(lastIdx)));
            }
        }

        sb.append("\n请根据以上详实量化数据与用户持仓成本，完成专业级明日走势推演与实战操作锦囊！");
        return sb.toString();
    }

    private CopilotPredictResponse buildPredictResponseFromAi(String symbol, String name, BigDecimal currentPrice,
                                                               BigDecimal holdCost, BigDecimal dilutedCost, Integer holdQty,
                                                               PivotPointsDTO pivot, String replyText, String model, long latency) {
        String verdict = "震荡偏多";
        int probability = 65;

        Pattern verdictPattern = Pattern.compile("【明日趋势定调】[:：]?\\s*([^\\n\\r]+)");
        Matcher vm = verdictPattern.matcher(replyText);
        if (vm.find()) {
            verdict = vm.group(1).trim().replaceAll("^[【\\[]+|[】\\]]+$", "");
        }

        Pattern probPattern = Pattern.compile("【多头预估胜率】[:：]?\\s*(\\d+)%?");
        Matcher pm = probPattern.matcher(replyText);
        if (pm.find()) {
            try {
                probability = Integer.parseInt(pm.group(1).trim());
            } catch (Exception ignored) {}
        }

        return CopilotPredictResponse.builder()
                .symbol(symbol)
                .name(name)
                .trendVerdict(verdict)
                .bullishProbability(probability)
                .holdCostPrice(holdCost)
                .dilutedCostPrice(dilutedCost)
                .currentPrice(currentPrice)
                .holdQuantity(holdQty)
                .pivotPoints(pivot)
                .analysisReport(replyText)
                .modelUsed(model)
                .latencyMs(latency)
                .timestamp(LocalDateTime.now().format(TIME_FORMATTER))
                .build();
    }

    private CopilotPredictResponse buildRuleBasedPredictResponse(String symbol, String name, BigDecimal currentPrice,
                                                                 BigDecimal holdCost, BigDecimal dilutedCost, Integer holdQty,
                                                                 KlineChartDTO kline, PivotPointsDTO pivot) {
        return buildRuleBasedPredictResponse(symbol, name, currentPrice, holdCost, dilutedCost, holdQty, kline, pivot, null);
    }

    private CopilotPredictResponse buildRuleBasedPredictResponse(String symbol, String name, BigDecimal currentPrice,
                                                                 BigDecimal holdCost, BigDecimal dilutedCost, Integer holdQty,
                                                                 KlineChartDTO kline, PivotPointsDTO pivot, PositionVO pos) {
        // 基于量化数据自动打分判定趋势定调与多头胜率
        int score = 50; // 基准 50%
        StringBuilder techSummary = new StringBuilder();

        // 结合大盘整体宏观情绪打分
        MarketOverviewDTO marketOverview = quoteService.getMarketOverview();
        if (marketOverview != null && marketOverview.getSentimentScore() != null) {
            if (marketOverview.getSentimentScore() >= 65) {
                score += 5;
                techSummary.append(String.format("- **宏观大盘环境**: 全市场情绪温度 %d 分 (%s)，两市交投 %s，具备较强顺风协同性；\n",
                        marketOverview.getSentimentScore(), marketOverview.getSentimentTitle(), marketOverview.getTurnoverStatus()));
            } else if (marketOverview.getSentimentScore() <= 40) {
                score -= 5;
                techSummary.append(String.format("- **宏观大盘环境**: 全市场情绪温度仅 %d 分 (%s)，大盘整体防御避险，需警惕系统性杀跌拖累；\n",
                        marketOverview.getSentimentScore(), marketOverview.getSentimentTitle()));
            } else {
                techSummary.append(String.format("- **宏观大盘环境**: 全市场情绪温度 %d 分 (%s)，%s，大势总体平稳；\n",
                        marketOverview.getSentimentScore(), marketOverview.getSentimentTitle(), marketOverview.getTurnoverStatus()));
            }
        }

        // 结合个股相对大盘强弱度 (RS Alpha) 打分
        if (pos != null && pos.getRelativeStrength() != null) {
            double rs = pos.getRelativeStrength().doubleValue();
            if (rs >= 1.0) {
                score += 5;
                techSummary.append(String.format("- **相对大盘强弱**: 相对基准 (%s) 跑赢 +%s%% (%s)，主力资金主动护盘意图明显；\n",
                        pos.getBenchmarkName(), pos.getRelativeStrength(), pos.getRelativeStrengthStatus()));
            } else if (rs <= -1.0) {
                score -= 5;
                techSummary.append(String.format("- **相对大盘强弱**: 相对基准 (%s) 跑输 %s%% (%s)，走势弱于大势，抛压尚未完全出清；\n",
                        pos.getBenchmarkName(), pos.getRelativeStrength(), pos.getRelativeStrengthStatus()));
            }
        }

        if (pivot != null && currentPrice != null) {
            if (currentPrice.compareTo(pivot.getPivot()) >= 0) {
                score += 10;
                techSummary.append(String.format("- **轴心状态**: 现价 (¥%s) 运行于多空分水岭 P (¥%s) 之上，多方掌握盘面主动权；\n", currentPrice, pivot.getPivot()));
            } else {
                score -= 10;
                techSummary.append(String.format("- **轴心状态**: 现价 (¥%s) 处于中轴 P (¥%s) 下方，短期处于空头消化整理格局；\n", currentPrice, pivot.getPivot()));
            }
        }


        if (kline != null && kline.getDates() != null && !kline.getDates().isEmpty()) {
            int lastIdx = kline.getDates().size() - 1;
            // 均线多空判断
            if (kline.getMa5() != null && kline.getMa20() != null && kline.getMa5().size() > lastIdx && kline.getMa20().size() > lastIdx) {
                BigDecimal ma5 = kline.getMa5().get(lastIdx);
                BigDecimal ma20 = kline.getMa20().get(lastIdx);
                if (ma5 != null && ma20 != null) {
                    if (ma5.compareTo(ma20) > 0) {
                        score += 8;
                        techSummary.append(String.format("- **均线排列**: MA5 (¥%s) 上穿/高于 MA20 (¥%s)，短期均线系统呈偏多发散形态；\n", ma5, ma20));
                    } else {
                        score -= 8;
                        techSummary.append(String.format("- **均线排列**: MA5 (¥%s) 处于 MA20 (¥%s) 之下，短期均线呈偏弱压制形态；\n", ma5, ma20));
                    }
                }
            }

            // MACD 判断
            if (kline.getMacdBar() != null && kline.getMacdBar().size() > lastIdx) {
                BigDecimal bar = kline.getMacdBar().get(lastIdx);
                if (bar != null) {
                    if (bar.compareTo(BigDecimal.ZERO) > 0) {
                        score += 7;
                        techSummary.append(String.format("- **MACD 动能**: 动能柱为红柱 (%s)，红柱扩张显示多方推升动能充沛；\n", bar));
                    } else {
                        score -= 7;
                        techSummary.append(String.format("- **MACD 动能**: 动能柱为绿柱 (%s)，空方动能仍处释放周期，需关注何时翻红；\n", bar));
                    }
                }
            }

            // KDJ 超买超卖
            if (kline.getKdjJ() != null && kline.getKdjJ().size() > lastIdx) {
                BigDecimal j = kline.getKdjJ().get(lastIdx);
                if (j != null) {
                    if (j.compareTo(new BigDecimal("100")) > 0) {
                        score -= 5;
                        techSummary.append(String.format("- **KDJ 指标**: J 值为 %s，进入超买极端过热区，谨防冲高回落，不可追涨；\n", j));
                    } else if (j.compareTo(BigDecimal.ZERO) < 0) {
                        score += 5;
                        techSummary.append(String.format("- **KDJ 指标**: J 值为 %s，处于超卖冰点钝化区，具备极高技术性超跌反弹动能；\n", j));
                    } else {
                        techSummary.append(String.format("- **KDJ 指标**: J 值为 %s，处于常态健康震荡区间；\n", j));
                    }
                }
            }
        }

        if (score > 85) score = 85;
        if (score < 25) score = 25;

        String verdict;
        if (score >= 70) {
            verdict = "强烈看多";
        } else if (score >= 58) {
            verdict = "震荡偏多";
        } else if (score >= 45) {
            verdict = "窄幅震荡";
        } else if (score >= 35) {
            verdict = "震荡偏空";
        } else {
            verdict = "破位防守";
        }

        StringBuilder report = new StringBuilder();
        report.append(String.format("【明日趋势定调】: %s\n", verdict));
        report.append(String.format("【多头预估胜率】: %d%%\n\n", score));

        report.append("### 📌 一、次日多空基准定调与概率评估\n");
        report.append(String.format("根据高精量化模型最新研判，**%s (%s)** 次日走势定调为 **【%s】**，明日多头胜率预估为 **%d%%**。\n", name, symbol, verdict, score));
        if (pivot != null) {
            report.append(String.format("预期次日核心交投博弈区间在 **¥%s (S1 支撑) ~ ¥%s (R1 阻力)** 之间展开。\n\n", pivot.getS1(), pivot.getR1()));
        } else {
            report.append("整体盘面以波段箱体震荡为主，建议按照点位节奏执行做T。\n\n");
        }

        report.append("### 📊 二、核心技术指标与网格点位拆解\n");
        if (techSummary.length() > 0) {
            report.append(techSummary);
        } else {
            report.append("- 行情与指标处于平稳震荡期，各技术指标中枢逐步收敛。\n");
        }
        if (pivot != null) {
            report.append(String.format("- **次日 Pivot 网格**: 轴心中轴 P=¥%s | 第一阻力 R1=¥%s | 第二阻力 R2=¥%s | 第一支撑 S1=¥%s | 第二支撑 S2=¥%s。\n\n",
                    pivot.getPivot(), pivot.getR1(), pivot.getR2(), pivot.getS1(), pivot.getS2()));
        }

        report.append("### 🎯 三、持仓生命线对比与实战做T锦囊\n");
        if (holdQty != null && holdQty > 0 && holdCost != null) {
            report.append(String.format("您的当前持仓为 **%d 股**，买入均价为 **¥%s**，做T摊薄保本价为 **¥%s**。\n",
                    holdQty, holdCost, dilutedCost != null ? dilutedCost : holdCost));

            if (currentPrice != null && dilutedCost != null) {
                if (currentPrice.compareTo(dilutedCost) > 0) {
                    report.append(String.format("- **浮盈安全垫状态**: 当前现价 (¥%s) 处于摊薄保本线 (¥%s) 之上，持仓具备充足安全气囊，心理优势极佳。\n", currentPrice, dilutedCost));
                } else {
                    report.append(String.format("- **保本线回补状态**: 当前现价 (¥%s) 接近或略低于摊薄保本线 (¥%s)，正是依托网格拉低均价的做T黄金期。\n", currentPrice, dilutedCost));
                }
            }

            if (pivot != null) {
                report.append(String.format("- **冲高做T减仓策略**: 明日盘中若快速脉冲至 **第一阻力位 R1 (¥%s)** 附近，建议挂单减仓 1000~2000 股锁利落袋，若强势放量突破则可上看 **R2 (¥%s)**；\n", pivot.getR1(), pivot.getR2()));
                report.append(String.format("- **回踩低吸回补策略**: 明日若受情绪扰动回踩至 **第一支撑位 S1 (¥%s)** 企稳，可择机补回同等仓位，进一步摊低综合保本均价；\n", pivot.getS1()));
                report.append(String.format("- **保本出逃底线**: 若盘中意外下破 **S2 (¥%s)** 且收盘未拉回，建议对加仓筹码果断止损离场，锁死本金回撤风险。\n\n", pivot.getS2()));
            }
        } else {
            report.append("- 当前未持有该标的。若明日回踩 S1 关键支撑位企稳，可考虑首笔 1~2 层仓位试盘低吸建仓。\n\n");
        }

        report.append("### 🛡️ 四、次日极端突发走势应对预案\n");
        report.append("- **大幅高开 (高开 > 1.5%)**: 切勿开盘无脑追涨，谨防诱多冲高回落，耐心等待回踩 P 轴分界点确认支撑后再作抉择；\n");
        report.append("- **大幅低开 (低开破位 S1)**: 观察前 15 分钟成交量承接力，若恐慌杀跌不放量，往往构成黄金坑低吸良机，切忌在最低点恐慌割肉。\n");

        return CopilotPredictResponse.builder()
                .symbol(symbol)
                .name(name)
                .trendVerdict(verdict)
                .bullishProbability(score)
                .holdCostPrice(holdCost)
                .dilutedCostPrice(dilutedCost)
                .currentPrice(currentPrice)
                .holdQuantity(holdQty)
                .pivotPoints(pivot)
                .analysisReport(report.toString())
                .modelUsed("量化规则推演引擎 (专业版)")
                .latencyMs(12L)
                .timestamp(LocalDateTime.now().format(TIME_FORMATTER))
                .build();
    }
}


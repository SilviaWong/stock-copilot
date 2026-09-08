package com.stock.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.common.BusinessException;
import com.stock.dto.OcrConfigRequest;
import com.stock.dto.OcrParseRequest;
import com.stock.dto.OcrTradeItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class VisionOcrService {

    private static final Logger log = LoggerFactory.getLogger(VisionOcrService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${stock.ai.base-url:https://api.openai.com/v1}")
    private String defaultBaseUrl;

    @Value("${stock.ai.api-key:}")
    private String defaultApiKey;

    @Value("${stock.ai.model:gpt-4o-mini}")
    private String defaultModel;

    private final HttpClient httpClient;

    public VisionOcrService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * 解析交易截图
     */
    public List<OcrTradeItem> parseTradeScreenshot(OcrParseRequest request) {
        if (!StringUtils.hasText(request.getImageBase64())) {
            throw new BusinessException("截图数据不能为空！");
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
            throw new BusinessException("未配置 API Key！请点击识别设置填入您的代理 API Key。");
        }

        // 解析并分离 Base64 数据与 MimeType
        String rawBase64 = request.getImageBase64().trim();
        String mimeType = "image/jpeg";
        String pureBase64 = rawBase64;

        if (rawBase64.startsWith("data:")) {
            int semiIdx = rawBase64.indexOf(';');
            int commaIdx = rawBase64.indexOf(',');
            if (semiIdx > 5 && commaIdx > semiIdx) {
                mimeType = rawBase64.substring(5, semiIdx);
                pureBase64 = rawBase64.substring(commaIdx + 1);
            }
        }

        // 清洗空白符并重新生成规范的 Data URL
        pureBase64 = pureBase64.replaceAll("\\s+", "");
        String fullDataUrl = "data:" + mimeType + ";base64," + pureBase64;

        String targetSymbol = request.getTargetSymbol() != null ? request.getTargetSymbol().trim() : null;
        String targetName = request.getTargetName() != null ? request.getTargetName().trim() : null;

        String targetUrl = resolveTargetUrl(protocol, baseUrl, customEndpoint, model);
        HttpRequest httpRequest = buildOcrHttpRequest(protocol, targetUrl, apiKey, model, fullDataUrl, pureBase64, mimeType, targetSymbol, targetName);

        try {
            log.info("【Stock Copilot】正在调用视觉模型: protocol={}, url={}, model={}, targetSymbol={}", protocol, targetUrl, model, targetSymbol);
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("大模型请求失败: status={}, url={}, body={}", response.statusCode(), targetUrl, response.body());
                String tip = generateErrorDiagnosis(response.statusCode(), targetUrl, protocol);
                throw new BusinessException("大模型接口调用失败 (" + response.statusCode() + "): " + tip + "\n返回内容: " + response.body());
            }

            String aiRawContent = extractContentByProtocol(protocol, response.body());
            return parseTradeItemsFromContent(aiRawContent, targetSymbol, targetName);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("视觉识别调用异常: ", e);
            throw new BusinessException("截图识别失败: " + e.getMessage());
        }
    }

    /**
     * 测试代理模型连通性 (发送简短轻量请求，检测 URL、协议、认证是否正常)
     */
    public Map<String, Object> testConnection(OcrConfigRequest config) {
        Map<String, Object> result = new HashMap<>();
        String protocol = (config != null && StringUtils.hasText(config.getProtocol()))
                ? config.getProtocol().trim().toUpperCase() : "OPENAI";
        String baseUrl = (config != null && StringUtils.hasText(config.getBaseUrl()))
                ? config.getBaseUrl().trim() : defaultBaseUrl;
        String apiKey = (config != null && StringUtils.hasText(config.getApiKey()))
                ? config.getApiKey().trim() : defaultApiKey;
        String model = (config != null && StringUtils.hasText(config.getModel()))
                ? config.getModel().trim() : defaultModel;
        String customEndpoint = config != null ? config.getCustomEndpoint() : null;

        String targetUrl = resolveTargetUrl(protocol, baseUrl, customEndpoint, model);
        result.put("targetUrl", targetUrl);
        result.put("protocol", protocol);
        result.put("model", model);

        if (!StringUtils.hasText(apiKey) && !"OLLAMA".equalsIgnoreCase(protocol)) {
            result.put("success", false);
            result.put("message", "API Key 不能为空！");
            return result;
        }

        long start = System.currentTimeMillis();
        try {
            HttpRequest httpRequest = buildPingHttpRequest(protocol, targetUrl, apiKey, model);
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;
            result.put("latencyMs", latency);
            result.put("statusCode", response.statusCode());

            if (response.statusCode() == 200) {
                result.put("success", true);
                result.put("message", "连接成功！目标服务器已响应 (耗时 " + latency + "ms)");
                result.put("raw", truncate(response.body(), 200));
            } else {
                result.put("success", false);
                String tip = generateErrorDiagnosis(response.statusCode(), targetUrl, protocol);
                result.put("message", "请求返回 HTTP " + response.statusCode() + "！" + tip);
                result.put("raw", truncate(response.body(), 300));
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            result.put("success", false);
            result.put("latencyMs", latency);
            result.put("message", "无法连通目标服务: " + e.getMessage() + "。请检查网络或 Base URL 格式。");
        }

        return result;
    }

    /**
     * 智能计算最终请求目标 URL (解决缺少 /v1 导致的 404 等问题)
     */
    public String resolveTargetUrl(String protocol, String baseUrl, String customEndpoint, String model) {
        if ("CUSTOM".equalsIgnoreCase(protocol) && StringUtils.hasText(customEndpoint)) {
            return customEndpoint.trim();
        }
        if (StringUtils.hasText(customEndpoint)) {
            return customEndpoint.trim();
        }

        String url = StringUtils.hasText(baseUrl) ? baseUrl.trim() : defaultBaseUrl;
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        String proto = StringUtils.hasText(protocol) ? protocol.trim().toUpperCase() : "OPENAI";

        switch (proto) {
            case "CLAUDE":
                if (url.endsWith("/messages")) {
                    return url;
                } else if (url.endsWith("/v1")) {
                    return url + "/messages";
                } else {
                    return url + "/v1/messages";
                }
            case "GEMINI":
                if (url.contains(":generateContent")) {
                    return url;
                } else if (url.endsWith("/v1beta")) {
                    return url + "/models/" + model + ":generateContent";
                } else {
                    return url + "/v1beta/models/" + model + ":generateContent";
                }
            case "OLLAMA":
                if (url.endsWith("/api/chat")) {
                    return url;
                } else {
                    return url + "/api/chat";
                }
            case "CUSTOM":
                return url;
            case "OPENAI":
            default:
                if (url.endsWith("/chat/completions")) {
                    return url;
                } else if (url.endsWith("/v1")) {
                    return url + "/chat/completions";
                } else if (url.contains("/v1/")) {
                    return url + "/chat/completions";
                } else {
                    // 核心修复！如果用户只填了域名如 https://api.myproxy.com，自动补全 /v1/chat/completions，防止 404
                    return url + "/v1/chat/completions";
                }
        }
    }

    private HttpRequest buildOcrHttpRequest(String protocol, String targetUrl, String apiKey, String model,
                                            String fullDataUrl, String pureBase64, String mimeType,
                                            String targetSymbol, String targetName) {
        try {
            String prompt = buildSystemPrompt(targetSymbol, targetName);
            String requestBody;

            String actualUrl = targetUrl;
            if ("GEMINI".equalsIgnoreCase(protocol) && StringUtils.hasText(apiKey) && !actualUrl.contains("key=")) {
                actualUrl = actualUrl + (actualUrl.contains("?") ? "&" : "?") + "key=" + apiKey;
            }

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(actualUrl))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json");

            switch (protocol) {
                case "CLAUDE":
                    builder.header("x-api-key", apiKey)
                           .header("anthropic-version", "2023-06-01")
                           .header("Authorization", "Bearer " + apiKey);
                    Map<String, Object> claudePayload = new HashMap<>();
                    claudePayload.put("model", model);
                    claudePayload.put("max_tokens", 8192);
                    claudePayload.put("temperature", 0.1);

                    Map<String, Object> claudeImageSource = new HashMap<>();
                    claudeImageSource.put("type", "base64");
                    claudeImageSource.put("media_type", mimeType);
                    claudeImageSource.put("data", pureBase64);

                    Map<String, Object> claudeImagePart = new HashMap<>();
                    claudeImagePart.put("type", "image");
                    claudeImagePart.put("source", claudeImageSource);

                    Map<String, Object> claudeTextPart = new HashMap<>();
                    claudeTextPart.put("type", "text");
                    claudeTextPart.put("text", prompt);

                    Map<String, Object> claudeUserMsg = new HashMap<>();
                    claudeUserMsg.put("role", "user");
                    claudeUserMsg.put("content", Arrays.asList(claudeImagePart, claudeTextPart));

                    claudePayload.put("messages", Collections.singletonList(claudeUserMsg));
                    requestBody = MAPPER.writeValueAsString(claudePayload);
                    break;

                case "GEMINI":
                    builder.header("x-goog-api-key", apiKey);
                    Map<String, Object> geminiPayload = new HashMap<>();
                    Map<String, Object> geminiInlineData = new HashMap<>();
                    // 注意：Google Gemini REST API 规定字段名必须是驼峰 mimeType 与 data
                    geminiInlineData.put("mimeType", mimeType);
                    geminiInlineData.put("data", pureBase64);

                    Map<String, Object> geminiPartImage = new HashMap<>();
                    // 注意：字段名必须是驼峰 inlineData，写成 inline_data 会触发 400 INVALID_ARGUMENT
                    geminiPartImage.put("inlineData", geminiInlineData);

                    Map<String, Object> geminiPartText = new HashMap<>();
                    geminiPartText.put("text", prompt);

                    Map<String, Object> geminiContent = new HashMap<>();
                    geminiContent.put("role", "user");
                    geminiContent.put("parts", Arrays.asList(geminiPartText, geminiPartImage));
                    geminiPayload.put("contents", Collections.singletonList(geminiContent));

                    Map<String, Object> geminiGenConfig = new HashMap<>();
                    geminiGenConfig.put("temperature", 0.1);
                    geminiGenConfig.put("maxOutputTokens", 8192);
                    geminiPayload.put("generationConfig", geminiGenConfig);

                    requestBody = MAPPER.writeValueAsString(geminiPayload);
                    break;

                case "OLLAMA":
                    if (StringUtils.hasText(apiKey)) {
                        builder.header("Authorization", "Bearer " + apiKey);
                    }
                    Map<String, Object> ollamaPayload = new HashMap<>();
                    ollamaPayload.put("model", model);
                    ollamaPayload.put("stream", false);

                    Map<String, Object> ollamaMsg = new HashMap<>();
                    ollamaMsg.put("role", "user");
                    ollamaMsg.put("content", prompt);
                    ollamaMsg.put("images", Collections.singletonList(pureBase64));
                    ollamaPayload.put("messages", Collections.singletonList(ollamaMsg));
                    requestBody = MAPPER.writeValueAsString(ollamaPayload);
                    break;

                case "CUSTOM":
                case "OPENAI":
                default:
                    builder.header("Authorization", "Bearer " + apiKey);
                    Map<String, Object> openAiPayload = new HashMap<>();
                    openAiPayload.put("model", model);
                    openAiPayload.put("temperature", 0.1);
                    openAiPayload.put("max_tokens", 8192);
                    openAiPayload.put("max_completion_tokens", 8192);

                    Map<String, Object> msg = new HashMap<>();
                    msg.put("role", "user");

                    Map<String, Object> textPart = new HashMap<>();
                    textPart.put("type", "text");
                    textPart.put("text", prompt);

                    Map<String, Object> imagePart = new HashMap<>();
                    imagePart.put("type", "image_url");
                    Map<String, String> urlObj = new HashMap<>();
                    urlObj.put("url", fullDataUrl);
                    urlObj.put("detail", "high");
                    imagePart.put("image_url", urlObj);

                    msg.put("content", Arrays.asList(textPart, imagePart));
                    openAiPayload.put("messages", Collections.singletonList(msg));
                    requestBody = MAPPER.writeValueAsString(openAiPayload);
                    break;
            }

            return builder.POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
        } catch (Exception e) {
            throw new BusinessException("构建请求失败: " + e.getMessage());
        }
    }

    private HttpRequest buildPingHttpRequest(String protocol, String targetUrl, String apiKey, String model) {
        try {
            String actualUrl = targetUrl;
            if ("GEMINI".equalsIgnoreCase(protocol) && StringUtils.hasText(apiKey) && !actualUrl.contains("key=")) {
                actualUrl = actualUrl + (actualUrl.contains("?") ? "&" : "?") + "key=" + apiKey;
            }

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(actualUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json");

            String requestBody;
            switch (protocol) {
                case "CLAUDE":
                    builder.header("x-api-key", apiKey)
                           .header("anthropic-version", "2023-06-01")
                           .header("Authorization", "Bearer " + apiKey);
                    Map<String, Object> cMsg = new HashMap<>();
                    cMsg.put("role", "user");
                    cMsg.put("content", "ping");
                    Map<String, Object> cPayload = new HashMap<>();
                    cPayload.put("model", model);
                    cPayload.put("max_tokens", 10);
                    cPayload.put("messages", Collections.singletonList(cMsg));
                    requestBody = MAPPER.writeValueAsString(cPayload);
                    break;

                case "GEMINI":
                    builder.header("x-goog-api-key", apiKey);
                    Map<String, Object> gPayload = new HashMap<>();
                    Map<String, Object> gPart = Collections.singletonMap("text", "ping");
                    Map<String, Object> gContent = new HashMap<>();
                    gContent.put("role", "user");
                    gContent.put("parts", Collections.singletonList(gPart));
                    gPayload.put("contents", Collections.singletonList(gContent));
                    Map<String, Object> gConfig = new HashMap<>();
                    gConfig.put("maxOutputTokens", 10);
                    gPayload.put("generationConfig", gConfig);
                    requestBody = MAPPER.writeValueAsString(gPayload);
                    break;

                case "OLLAMA":
                    if (StringUtils.hasText(apiKey)) {
                        builder.header("Authorization", "Bearer " + apiKey);
                    }
                    Map<String, Object> oMsg = new HashMap<>();
                    oMsg.put("role", "user");
                    oMsg.put("content", "ping");
                    Map<String, Object> oPayload = new HashMap<>();
                    oPayload.put("model", model);
                    oPayload.put("stream", false);
                    oPayload.put("messages", Collections.singletonList(oMsg));
                    requestBody = MAPPER.writeValueAsString(oPayload);
                    break;

                case "CUSTOM":
                case "OPENAI":
                default:
                    builder.header("Authorization", "Bearer " + apiKey);
                    Map<String, Object> openMsg = new HashMap<>();
                    openMsg.put("role", "user");
                    openMsg.put("content", "ping");
                    Map<String, Object> openPayload = new HashMap<>();
                    openPayload.put("model", model);
                    openPayload.put("max_tokens", 10);
                    openPayload.put("messages", Collections.singletonList(openMsg));
                    requestBody = MAPPER.writeValueAsString(openPayload);
                    break;
            }

            return builder.POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
        } catch (Exception e) {
            throw new BusinessException("构建测试请求失败: " + e.getMessage());
        }
    }

    private String extractContentByProtocol(String protocol, String responseJson) {
        try {
            JsonNode root = MAPPER.readTree(responseJson);

            // 统一错误结构检查
            if (root.has("error")) {
                JsonNode errNode = root.path("error");
                String errMsg = errNode.has("message") ? errNode.path("message").asText() : errNode.toString();
                throw new BusinessException("大模型接口返回错误: " + errMsg);
            }

            switch (protocol) {
                case "CLAUDE":
                    JsonNode cContent = root.path("content");
                    if (cContent.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode node : cContent) {
                            if ("text".equals(node.path("type").asText())) {
                                sb.append(node.path("text").asText());
                            }
                        }
                        return sb.toString();
                    }
                    return cContent.asText();

                case "GEMINI":
                    JsonNode candidates = root.path("candidates");
                    if (candidates.isArray() && !candidates.isEmpty()) {
                        JsonNode cand0 = candidates.get(0);
                        String finishReason = cand0.path("finishReason").asText(null);
                        if ("MAX_TOKENS".equalsIgnoreCase(finishReason) || "LENGTH".equalsIgnoreCase(finishReason)) {
                            log.warn("【大模型输出预警】Gemini 返回 finishReason={}，说明输出因达到 Token 长度限制被截断！", finishReason);
                        }
                        JsonNode parts = cand0.path("content").path("parts");
                        if (parts.isArray() && !parts.isEmpty()) {
                            StringBuilder sb = new StringBuilder();
                            for (JsonNode partNode : parts) {
                                if (partNode.has("text")) {
                                    sb.append(partNode.get("text").asText());
                                }
                            }
                            return sb.toString();
                        }
                    }
                    throw new BusinessException("Gemini 未返回有效候选内容: " + truncate(responseJson, 200));

                case "OLLAMA":
                    return root.path("message").path("content").asText();

                case "CUSTOM":
                case "OPENAI":
                default:
                    JsonNode choices = root.path("choices");
                    if (!choices.isArray() || choices.isEmpty()) {
                        throw new BusinessException("OpenAI 兼容模型未返回有效 choices: " + truncate(responseJson, 200));
                    }
                    JsonNode choice0 = choices.get(0);
                    String finishReason = choice0.path("finish_reason").asText(null);
                    if ("length".equalsIgnoreCase(finishReason)) {
                        log.warn("【大模型输出预警】OpenAI 兼容接口返回 finish_reason=length，说明输出因达到 Token 长度限制被截断！");
                    }
                    JsonNode contentNode = choice0.path("message").path("content");
                    return extractTextFromContentNode(contentNode);
            }
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("提取大模型响应文本失败: json={}", responseJson, e);
            throw new BusinessException("解析大模型响应失败: " + e.getMessage());
        }
    }

    private String extractTextFromContentNode(JsonNode contentNode) {
        if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : contentNode) {
                if (part.has("text")) {
                    sb.append(part.get("text").asText());
                } else if (part.isTextual()) {
                    sb.append(part.asText());
                }
            }
            return sb.toString();
        }
        return contentNode.toString();
    }

    public List<OcrTradeItem> parseTradeItemsFromContent(String content, String targetSymbol, String targetName) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException("大模型返回内容为空");
        }

        // 去除 markdown 标记代码块 ```json ... ```
        String cleanJson = content.trim();
        if (cleanJson.startsWith("```json")) {
            cleanJson = cleanJson.substring(7);
        } else if (cleanJson.startsWith("```")) {
            cleanJson = cleanJson.substring(3);
        }
        if (cleanJson.endsWith("```")) {
            cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
        }
        cleanJson = cleanJson.trim();

        int startIdx = cleanJson.indexOf('[');
        int endIdx = cleanJson.lastIndexOf(']');
        if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
            cleanJson = cleanJson.substring(startIdx, endIdx + 1);
        }

        List<OcrTradeItem> items = null;
        try {
            items = MAPPER.readValue(cleanJson, new TypeReference<List<OcrTradeItem>>() {});
        } catch (Exception parseEx) {
            log.warn("大模型原始 JSON 解析失败，尝试自动容错修复截断数据: cleanJson={}", cleanJson);

            // 策略1：截取到最后一个完整的 '}' 并补齐 ']' 闭合数组
            String repairedJson = tryRepairTruncatedJson(cleanJson);
            try {
                items = MAPPER.readValue(repairedJson, new TypeReference<List<OcrTradeItem>>() {});
                log.info("【容错恢复成功】已自动修复被截断的 JSON 数组，成功救回 {} 笔完整交易记录！", items.size());
            } catch (Exception secondEx) {
                // 策略2：正则逐个扫描提取已完整闭合的 { ... } 对象
                items = extractValidObjectsByRegex(cleanJson);
                if (items == null || items.isEmpty()) {
                    log.error("大模型输出格式无法修复: cleanJson={}", cleanJson, parseEx);
                    throw new BusinessException("大模型返回内容被截断且无法解析，请检查代理模型 Token 限制或重试。\n返回片段: " + truncate(cleanJson, 200));
                }
                log.info("【容错恢复成功】通过对象扫描成功救回 {} 笔完整交易记录！", items.size());
            }
        }

        int currentYear = LocalDate.now().getYear();
        for (OcrTradeItem item : items) {
            // 如果用户明确指定了标的，且识别出的标的代码为空或无法匹配，强制以指定标的为准
            if (StringUtils.hasText(targetSymbol)) {
                if (!StringUtils.hasText(item.getSymbol()) || !item.getSymbol().matches("\\d{6}")) {
                    item.setSymbol(targetSymbol);
                }
                if (!StringUtils.hasText(item.getName()) && StringUtils.hasText(targetName)) {
                    item.setName(targetName);
                }
            }

            if (StringUtils.hasText(item.getAction())) {
                item.setAction(item.getAction().toUpperCase().contains("SELL") || item.getAction().contains("卖") ? "SELL" : "BUY");
            } else {
                item.setAction("BUY");
            }
            if (item.getQuantity() != null) {
                item.setQuantity(Math.abs(item.getQuantity()));
            }
            if (item.getFee() == null) {
                item.setFee(BigDecimal.ZERO);
            }
            if (item.getPrice() != null && item.getQuantity() != null && item.getAmount() == null) {
                item.setAmount(item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            }
            item.setTradeTime(normalizeTradeTime(item.getTradeTime(), currentYear));
            if (!StringUtils.hasText(item.getStrategyTag())) {
                item.setStrategyTag("截图导入");
            }
        }
        return items;
    }

    /**
     * 智能容错：自动修复因 Token 长度限制被截断的 JSON 数组
     */
    public String tryRepairTruncatedJson(String json) {
        if (!StringUtils.hasText(json)) {
            return json;
        }
        int start = json.indexOf('[');
        if (start == -1) {
            start = json.indexOf('{');
            if (start != -1) {
                int lastBrace = json.lastIndexOf('}');
                if (lastBrace > start) {
                    return "[" + json.substring(start, lastBrace + 1) + "]";
                }
            }
            return json;
        }

        int lastBrace = json.lastIndexOf('}');
        if (lastBrace > start) {
            return json.substring(start, lastBrace + 1) + "\n]";
        }
        return json;
    }

    /**
     * 极端容错：正则逐个提取所有有效闭合的 JSON 对象
     */
    public List<OcrTradeItem> extractValidObjectsByRegex(String text) {
        List<OcrTradeItem> list = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return list;
        }
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf('{', i);
            if (start == -1) break;

            int depth = 0;
            int end = -1;
            boolean inQuote = false;
            boolean escape = false;

            for (int j = start; j < text.length(); j++) {
                char c = text.charAt(j);
                if (escape) {
                    escape = false;
                    continue;
                }
                if (c == '\\') {
                    escape = true;
                    continue;
                }
                if (c == '"') {
                    inQuote = !inQuote;
                    continue;
                }
                if (!inQuote) {
                    if (c == '{') {
                        depth++;
                    } else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            end = j;
                            break;
                        }
                    }
                }
            }

            if (end != -1) {
                String candidate = text.substring(start, end + 1);
                try {
                    OcrTradeItem item = MAPPER.readValue(candidate, OcrTradeItem.class);
                    if (item != null && (StringUtils.hasText(item.getSymbol()) || item.getPrice() != null)) {
                        list.add(item);
                    }
                } catch (Exception ignored) {
                }
                i = end + 1;
            } else {
                break;
            }
        }
        return list;
    }

    private String generateErrorDiagnosis(int statusCode, String targetUrl, String protocol) {
        if (statusCode == 400) {
            return "【排查提示】HTTP 400 请求参数错误 (INVALID_ARGUMENT)！\n"
                    + "常见原因：\n"
                    + "1. 截图文件过大超过模型单次请求限制（前端已开启智能高清压缩，请重新粘贴截图再试）；\n"
                    + "2. 请求协议与代理平台不匹配（若使用的是 OneAPI/New-API 聚合中转站，协议强烈建议选择【OpenAI 兼容协议】，模型填 gemini-1.5-flash 即可；若选择【Google Gemini 协议】，目标地址必须是原生支持 Gemini /v1beta 的官方或反代）；\n"
                    + "3. 模型名称填写有误或代理账号无该模型权限。";
        }
        if (statusCode == 404) {
            return "【排查提示】HTTP 404 说明代理服务器没有找到请求路径！目标调用地址为: " + targetUrl
                    + "。\n可能原因：\n"
                    + "1. 代理服务器的基础路径可能需要包含 /v1（例如中转站需写成 https://proxy.domain.com/v1）；\n"
                    + "2. 协议选择不匹配（例如代理为 Claude 协议请切换为 Claude，OpenAI 协议切换为 OpenAI）；\n"
                    + "3. 您也可以直接在界面展开【自定义完整 URL】填入确切的 POST 地址。";
        }
        if (statusCode == 401 || statusCode == 403) {
            return "【排查提示】HTTP " + statusCode + " 认证失败！请检查您的 API Key 是否正确、未过期，或代理平台账户额度是否充足。";
        }
        return "目标服务器返回异常状态码 " + statusCode;
    }

    private String buildSystemPrompt(String targetSymbol, String targetName) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专业精准的中国券商交易记录与对账单截图解析助手。\n");
        sb.append("【任务核心】：仔细阅读截图中展示的所有成交流水记录卡片，并全部提取为结构化数据。\n\n");

        sb.append("【重要视觉识别规则 - 卡片式流水列表】：\n");
        sb.append("1. 【全量识别】：截图中通常纵向展示多个独立的成交记录卡片，每一个以大号红字【买入】或蓝字【卖出】开头的区域为一笔独立交易（例如图中通常有 3~10 笔甚至更多流水）。请务必从上至下完整扫描提取图中的【每一笔】记录，切勿只识别前 1~2 笔！\n");
        sb.append("2. 【买卖方向(action)】：认准卡片左上角大字：“买入”填写 \"BUY\"，“卖出”填写 \"SELL\"；\n");
        sb.append("3. 【成交单价(price)】：严格提取“成交价格”标签后的数值（例如 2.084, 2.125, 1.998, 2.015 等），【千万不要】误取右上角的“现价”！\n");
        sb.append("4. 【成交数量(quantity)】：提取“成交数量”后的数值，去除千分位逗号（如 1,000 提取为 1000，2,000 提取为 2000）；\n");
        sb.append("5. 【成交金额(amount)】：提取“成交金额”或“发生金额”后的数值（如 2084.00, 4250.00, 1998.20 等）；\n");
        sb.append("6. 【交易费用(fee)】：提取“交易费用”后的数值；若显示为 \"--\" 或 0，填 0；若显示具体数字如 0.20，填入对应数值；\n");
        sb.append("7. 【成交时间(tradeTime)】：读取卡片时间，包含“今日”或具体日期，格式如 \"今日 14:12:04\" 或 \"2026-09-04 14:47:04\"；\n");

        if (StringUtils.hasText(targetSymbol)) {
            sb.append("\n【重要标的绑定】：用户已指定此截图归属于标的：代码 [").append(targetSymbol).append("]");
            if (StringUtils.hasText(targetName)) {
                sb.append("，名称 [").append(targetName).append("]");
            }
            sb.append("。请在返回的【每一笔】交易记录中将 symbol 填为 \"")
              .append(targetSymbol).append("\"，name 填为 \"")
              .append(StringUtils.hasText(targetName) ? targetName : "").append("\"！\n\n");
        } else {
            sb.append("8. 【标的代码与名称】：若截图中未显示代码，可从页面顶部标题或上下文提取，代码未找到可填空字符串。\n\n");
        }

        sb.append("【输出格式约束】：请直接输出纯 JSON 数组（紧凑输出，不要有任何多余的废话、解释或 markdown 外的文字）：\n");
        sb.append("[{\"symbol\":\"").append(StringUtils.hasText(targetSymbol) ? targetSymbol : "159242")
          .append("\",\"name\":\"").append(StringUtils.hasText(targetName) ? targetName : "创业板人工智能ETF大成")
          .append("\",\"action\":\"BUY\",\"price\":2.084,\"quantity\":1000,\"amount\":2084.0,\"fee\":0.0,\"tradeTime\":\"今日 14:12:04\"}, ...]");
        return sb.toString();
    }

    private String normalizeTradeTime(String rawTime, int currentYear) {
        if (!StringUtils.hasText(rawTime)) {
            return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        String t = rawTime.trim();
        // 兼容中国券商常见的 "今日 14:12:04" 或 "今天 10:10:11"
        if (t.contains("今日") || t.contains("今天")) {
            String timePart = t.replace("今日", "").replace("今天", "").trim();
            if (timePart.matches("^\\d{1,2}:\\d{1,2}$")) {
                timePart = timePart + ":00";
            }
            return LocalDate.now().toString() + " " + timePart;
        }
        // 兼容 "昨日 14:12:04" 或 "昨天 10:10:11"
        if (t.contains("昨日") || t.contains("昨天")) {
            String timePart = t.replace("昨日", "").replace("昨天", "").trim();
            if (timePart.matches("^\\d{1,2}:\\d{1,2}$")) {
                timePart = timePart + ":00";
            }
            return LocalDate.now().minusDays(1).toString() + " " + timePart;
        }
        if (t.matches("^\\d{1,2}[-/]\\d{1,2}\\s+\\d{1,2}:\\d{1,2}(:\\d{1,2})?$")) {
            t = currentYear + "-" + t.replace('/', '-');
        }
        if (t.matches("^\\d{1,2}:\\d{1,2}(:\\d{1,2})?$")) {
            t = LocalDate.now().toString() + " " + t;
        }
        if (t.matches("^\\d{4}-\\d{1,2}-\\d{1,2}\\s+\\d{1,2}:\\d{1,2}$")) {
            t = t + ":00";
        }
        return t;
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }
}

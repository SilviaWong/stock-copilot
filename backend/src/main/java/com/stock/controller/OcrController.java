package com.stock.controller;

import com.stock.common.Result;
import com.stock.dto.OcrConfigRequest;
import com.stock.dto.OcrParseRequest;
import com.stock.dto.OcrTradeItem;
import com.stock.service.VisionOcrService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/ocr")
@CrossOrigin
public class OcrController {

    @Autowired
    private VisionOcrService visionOcrService;

    /**
     * 测试代理模型连通性 (诊断 404 / 401 / 协议匹配等问题)
     */
    @PostMapping("/test-connection")
    public Result<Map<String, Object>> testConnection(@RequestBody OcrConfigRequest config) {
        Map<String, Object> result = visionOcrService.testConnection(config);
        return Result.success(result);
    }

    /**
     * 调用多模态视觉大模型解析交易截图
     */
    @PostMapping("/parse-trades")
    public Result<List<OcrTradeItem>> parseScreenshotTrades(@RequestBody OcrParseRequest request) {
        List<OcrTradeItem> items = visionOcrService.parseTradeScreenshot(request);
        return Result.success(items);
    }

    /**
     * 智能解析直接复制粘贴的券商对账单纯文本 (免模型备选方案)
     */
    @PostMapping("/parse-text")
    public Result<List<OcrTradeItem>> parseTextTrades(@RequestBody String rawText) {
        List<OcrTradeItem> list = parseTextContent(rawText);
        return Result.success(list);
    }

    private List<OcrTradeItem> parseTextContent(String text) {
        List<OcrTradeItem> results = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return results;
        }

        String[] lines = text.split("\n");
        // 匹配 6 位数字代码
        Pattern symbolPattern = Pattern.compile("(\\b[03568]\\d{5}\\b)");
        // 匹配价格与数量浮点数
        Pattern numberPattern = Pattern.compile("(\\d+\\.\\d{1,4}|\\d+)");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.length() < 5) continue;

            Matcher symbolMatcher = symbolPattern.matcher(trimmed);
            if (symbolMatcher.find()) {
                String symbol = symbolMatcher.group(1);
                String action = (trimmed.contains("卖") || trimmed.toUpperCase().contains("SELL")) ? "SELL" : "BUY";

                List<BigDecimal> numbers = new ArrayList<>();
                Matcher numMatcher = numberPattern.matcher(trimmed);
                while (numMatcher.find()) {
                    String numStr = numMatcher.group(1);
                    if (!numStr.equals(symbol)) {
                        try {
                            numbers.add(new BigDecimal(numStr));
                        } catch (Exception ignored) {}
                    }
                }

                BigDecimal price = BigDecimal.ZERO;
                int quantity = 0;
                if (!numbers.isEmpty()) {
                    // 通常价格带有小数或较小，数量为整百
                    for (BigDecimal n : numbers) {
                        if (n.scale() > 0 || (n.compareTo(new BigDecimal("100")) < 0 && price.compareTo(BigDecimal.ZERO) == 0)) {
                            price = n;
                        } else if (n.remainder(new BigDecimal("100")).compareTo(BigDecimal.ZERO) == 0 && quantity == 0) {
                            quantity = n.intValue();
                        }
                    }
                }

                results.add(OcrTradeItem.builder()
                        .symbol(symbol)
                        .name(symbol)
                        .action(action)
                        .price(price.compareTo(BigDecimal.ZERO) > 0 ? price : new BigDecimal("1.0000"))
                        .quantity(quantity > 0 ? quantity : 100)
                        .fee(new BigDecimal("5.00"))
                        .strategyTag("文本导入")
                        .notes(trimmed)
                        .build());
            }
        }
        return results;
    }
}

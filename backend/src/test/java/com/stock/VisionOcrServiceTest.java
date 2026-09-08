package com.stock;

import com.stock.dto.OcrTradeItem;
import com.stock.service.VisionOcrService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

@SpringBootTest
public class VisionOcrServiceTest {

    @Autowired
    private VisionOcrService visionOcrService;

    @Test
    void testRepairTruncatedJson() {
        // 用户真实报错日志中的截断内容 (在第二笔的 action 处被 Token 限制截断)
        String truncatedJson = "[\n" +
                "  {\n" +
                "    \"symbol\": \"159242\",\n" +
                "    \"name\": \"创业板人工智能ETF大成\",\n" +
                "    \"action\": \"BUY\",\n" +
                "    \"price\": 2.084,\n" +
                "    \"quantity\": 1000,\n" +
                "    \"amount\": 2084.0,\n" +
                "    \"fee\": 0.0,\n" +
                "    \"tradeTime\": \"2024-09-05 14:12:04\",\n" +
                "    \"notes\": \"截图自动识别\"\n" +
                "  },\n" +
                "  {\n" +
                "    \"symbol\": \"159242\",\n" +
                "    \"name\": \"创业板人工智能ETF大成\",\n" +
                "    \"action\": \"";

        // 测试自动容错解析
        List<OcrTradeItem> items = visionOcrService.parseTradeItemsFromContent(truncatedJson, "159242", "创业板人工智能ETF大成");

        Assertions.assertNotNull(items);
        Assertions.assertEquals(1, items.size());

        OcrTradeItem item = items.get(0);
        Assertions.assertEquals("159242", item.getSymbol());
        Assertions.assertEquals("创业板人工智能ETF大成", item.getName());
        Assertions.assertEquals("BUY", item.getAction());
        Assertions.assertEquals(0, new BigDecimal("2.084").compareTo(item.getPrice()));
        Assertions.assertEquals(1000, item.getQuantity());
        Assertions.assertEquals(0, new BigDecimal("2084.0").compareTo(item.getAmount()));
        Assertions.assertEquals("2024-09-05 14:12:04", item.getTradeTime());

        System.out.println("====== 成功从截断的 JSON 中自动救回第一笔完整交易记录 ======");
        System.out.println("救回记录: " + item.getSymbol() + " " + item.getName() + " " + item.getAction() + " ¥" + item.getPrice() + " x" + item.getQuantity());
    }

    @Test
    void testRegexObjectExtraction() {
        // 多个散落的对象，没有首尾方括号
        String brokenText = "这里是识别内容：\n" +
                "{\"symbol\": \"510300\", \"name\": \"沪深300ETF\", \"action\": \"BUY\", \"price\": 3.985, \"quantity\": 1000, \"amount\": 3985.0}\n" +
                "一些无效文字干扰\n" +
                "{\"symbol\": \"159915\", \"name\": \"创业板ETF\", \"action\": \"SELL\", \"price\": 1.850, \"quantity\": 2000, \"amount\": 3700.0}\n" +
                "未闭合的对象 {\"symbol\": \"600519\", \"action\": \"";

        List<OcrTradeItem> items = visionOcrService.extractValidObjectsByRegex(brokenText);
        Assertions.assertEquals(2, items.size());
        Assertions.assertEquals("510300", items.get(0).getSymbol());
        Assertions.assertEquals("159915", items.get(1).getSymbol());
    }

    @Test
    void testParseAllFourCardsInUserScreenshot() {
        String json = "[\n" +
                "  {\"symbol\":\"159242\",\"name\":\"创业板人工智能ETF大成\",\"action\":\"BUY\",\"price\":2.084,\"quantity\":1000,\"amount\":2084.0,\"fee\":0.0,\"tradeTime\":\"今日 14:12:04\"},\n" +
                "  {\"symbol\":\"159242\",\"name\":\"创业板人工智能ETF大成\",\"action\":\"SELL\",\"price\":2.125,\"quantity\":2000,\"amount\":4250.0,\"fee\":0.0,\"tradeTime\":\"今日 10:10:11\"},\n" +
                "  {\"symbol\":\"159242\",\"name\":\"创业板人工智能ETF大成\",\"action\":\"BUY\",\"price\":1.998,\"quantity\":1000,\"amount\":1998.2,\"fee\":0.2,\"tradeTime\":\"2026-09-04 14:47:04\"},\n" +
                "  {\"symbol\":\"159242\",\"name\":\"创业板人工智能ETF大成\",\"action\":\"BUY\",\"price\":2.015,\"quantity\":1000,\"amount\":2015.2,\"fee\":0.2,\"tradeTime\":\"2026-09-02 10:10:34\"}\n" +
                "]";

        List<OcrTradeItem> items = visionOcrService.parseTradeItemsFromContent(json, "159242", "创业板人工智能ETF大成");
        Assertions.assertEquals(4, items.size());

        Assertions.assertEquals("BUY", items.get(0).getAction());
        Assertions.assertEquals(0, new BigDecimal("2.084").compareTo(items.get(0).getPrice()));
        Assertions.assertEquals(1000, items.get(0).getQuantity());
        Assertions.assertTrue(items.get(0).getTradeTime().contains("14:12:04"));

        Assertions.assertEquals("SELL", items.get(1).getAction());
        Assertions.assertEquals(0, new BigDecimal("2.125").compareTo(items.get(1).getPrice()));
        Assertions.assertEquals(2000, items.get(1).getQuantity());

        Assertions.assertEquals("BUY", items.get(2).getAction());
        Assertions.assertEquals(0, new BigDecimal("1.998").compareTo(items.get(2).getPrice()));
        Assertions.assertEquals(0, new BigDecimal("0.2").compareTo(items.get(2).getFee()));

        Assertions.assertEquals("BUY", items.get(3).getAction());
        Assertions.assertEquals(0, new BigDecimal("2.015").compareTo(items.get(3).getPrice()));
        Assertions.assertEquals(0, new BigDecimal("0.2").compareTo(items.get(3).getFee()));

        System.out.println("====== 4 笔卡片流水全部精准解析，'今日'自动转为合法日期时间 ======");
    }
}

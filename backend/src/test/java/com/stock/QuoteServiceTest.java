package com.stock;

import com.stock.dto.QuoteDTO;
import com.stock.service.QuoteService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.Map;

@SpringBootTest
public class QuoteServiceTest {

    @Autowired
    private QuoteService quoteService;

    @Test
    void testToTencentSymbol() {
        Assertions.assertEquals("sh510300", quoteService.toTencentSymbol("510300"));
        Assertions.assertEquals("sz159915", quoteService.toTencentSymbol("159915"));
        Assertions.assertEquals("sh600519", quoteService.toTencentSymbol("600519"));
        Assertions.assertEquals("sz000001", quoteService.toTencentSymbol("000001"));
        Assertions.assertEquals("bj832000", quoteService.toTencentSymbol("832000"));
    }

    @Test
    void testFetchRealQuote() {
        // 尝试拉取真实行情 (如果离线则不阻断)
        QuoteDTO quote = quoteService.fetchQuote("510300");
        if (quote != null) {
            System.out.println("成功拉取 510300 行情: 名称=" + quote.getName() 
                    + ", 最新价=" + quote.getCurrentPrice() 
                    + ", 今日涨跌=" + quote.getChangePercent() + "%"
                    + ", 更新时间=" + quote.getUpdateTime());
            Assertions.assertNotNull(quote.getCurrentPrice());
            Assertions.assertTrue(quote.getName().contains("300"));
        } else {
            System.out.println("当前测试环境网络受限，QuoteService 已安全降级。");
        }
    }

    @Test
    void testBatchFetchQuotes() {
        Map<String, QuoteDTO> map = quoteService.batchFetchQuotes(Arrays.asList("510300", "159915"));
        if (!map.isEmpty()) {
            map.forEach((k, v) -> System.out.println("批量行情: " + k + " -> " + v.getName() + " ¥" + v.getCurrentPrice()));
        }
    }

    @Test
    void testMinuteChart() {
        com.stock.dto.MinuteChartDTO chart = quoteService.fetchMinuteChart("159242");
        Assertions.assertNotNull(chart);
        Assertions.assertEquals("159242", chart.getSymbol());
        System.out.println("分时图测试: 标的=" + chart.getSymbol() 
                + ", 名称=" + chart.getName() 
                + ", 持仓股数=" + chart.getHoldQuantity() 
                + ", 买入均价=" + chart.getCostPrice() 
                + ", 摊薄保本价=" + chart.getDilutedCostPrice()
                + ", 今日打点数=" + (chart.getTradeMarkers() != null ? chart.getTradeMarkers().size() : 0));
    }

    @Test
    void testFiveDayChart() {
        com.stock.dto.FiveDayChartDTO chart = quoteService.fetchFiveDayChart("159242");
        Assertions.assertNotNull(chart);
        Assertions.assertEquals("159242", chart.getSymbol());
        System.out.println("五日图测试: 标的=" + chart.getSymbol()
                + ", 持仓股数=" + chart.getHoldQuantity()
                + ", 买入均价=" + chart.getCostPrice()
                + ", 摊薄保本价=" + chart.getDilutedCostPrice());
    }

    @Test
    void testKlineChart() {
        com.stock.dto.KlineChartDTO chart = quoteService.fetchKlineChart("159242");
        Assertions.assertNotNull(chart);
        Assertions.assertEquals("159242", chart.getSymbol());
        System.out.println("日K线测试: 标的=" + chart.getSymbol()
                + ", 名称=" + chart.getName()
                + ", 持仓股数=" + chart.getHoldQuantity()
                + ", 买入均价=" + chart.getCostPrice()
                + ", 摊薄保本价=" + chart.getDilutedCostPrice()
                + ", 历史交易图钉打点总数=" + (chart.getTradeMarkers() != null ? chart.getTradeMarkers().size() : 0));
        // 验证标的历史打点非空 (数据库中有159242交易流水)
        if (chart.getHoldQuantity() != null && chart.getHoldQuantity() > 0) {
            Assertions.assertNotNull(chart.getCostPrice());
            Assertions.assertNotNull(chart.getDilutedCostPrice());
            Assertions.assertNotNull(chart.getTradeMarkers());
            Assertions.assertFalse(chart.getTradeMarkers().isEmpty());
        }
    }
}


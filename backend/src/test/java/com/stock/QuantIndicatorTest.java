package com.stock;

import com.stock.dto.KlineChartDTO;
import com.stock.dto.PivotPointsDTO;
import com.stock.service.QuantIndicatorService;
import com.stock.service.QuoteService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@SpringBootTest
public class QuantIndicatorTest {

    @Autowired
    private QuantIndicatorService quantIndicatorService;

    @Autowired
    private QuoteService quoteService;

    @Test
    void testPivotPointsCalculation() {
        // High = 10.0, Low = 8.0, Close = 9.0
        BigDecimal high = new BigDecimal("10.00");
        BigDecimal low = new BigDecimal("8.00");
        BigDecimal close = new BigDecimal("9.00");

        PivotPointsDTO pivot = quantIndicatorService.calculatePivotPoints(
                "TEST", "2026-09-10", high, low, close, close
        );

        Assertions.assertNotNull(pivot);
        Assertions.assertEquals(new BigDecimal("9.000"), pivot.getPivot());
        Assertions.assertEquals(new BigDecimal("10.000"), pivot.getR1());
        Assertions.assertEquals(new BigDecimal("11.000"), pivot.getR2());
        Assertions.assertEquals(new BigDecimal("8.000"), pivot.getS1());
        Assertions.assertEquals(new BigDecimal("7.000"), pivot.getS2());
        Assertions.assertNotNull(pivot.getStatus());
        Assertions.assertNotNull(pivot.getActionTip());
        System.out.println("Pivot Points 结果: " + pivot);
    }

    @Test
    void testBollCalculation() {
        List<BigDecimal> closes = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            closes.add(new BigDecimal("10.00"));
        }

        QuantIndicatorService.BollResult boll = quantIndicatorService.calculateBoll(closes, 20, 2.0);
        Assertions.assertNotNull(boll);
        Assertions.assertNull(boll.getMid().get(0));
        Assertions.assertNotNull(boll.getMid().get(20));
        // 常数序列标准差为 0，mid == upper == lower == 10.000
        Assertions.assertEquals(new BigDecimal("10.000"), boll.getMid().get(20));
        Assertions.assertEquals(new BigDecimal("10.000"), boll.getUpper().get(20));
        Assertions.assertEquals(new BigDecimal("10.000"), boll.getLower().get(20));
    }

    @Test
    void testMacdCalculation() {
        List<BigDecimal> closes = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            closes.add(BigDecimal.valueOf(10.0 + i * 0.1));
        }

        QuantIndicatorService.MacdResult macd = quantIndicatorService.calculateMacd(closes, 12, 26, 9);
        Assertions.assertNotNull(macd);
        Assertions.assertEquals(30, macd.getDif().size());
        Assertions.assertEquals(30, macd.getDea().size());
        Assertions.assertEquals(30, macd.getBar().size());

        // 验证柱状线公式: BAR = 2 * (DIF - DEA)
        BigDecimal dif = macd.getDif().get(25);
        BigDecimal dea = macd.getDea().get(25);
        BigDecimal bar = macd.getBar().get(25);
        BigDecimal expectedBar = dif.subtract(dea).multiply(new BigDecimal("2")).setScale(3, java.math.RoundingMode.HALF_UP);
        Assertions.assertEquals(expectedBar, bar);
    }

    @Test
    void testKdjCalculation() {
        List<BigDecimal> highs = new ArrayList<>();
        List<BigDecimal> lows = new ArrayList<>();
        List<BigDecimal> closes = new ArrayList<>();

        for (int i = 1; i <= 20; i++) {
            highs.add(BigDecimal.valueOf(12.0 + i * 0.1));
            lows.add(BigDecimal.valueOf(9.0 + i * 0.1));
            closes.add(BigDecimal.valueOf(10.5 + i * 0.1));
        }

        QuantIndicatorService.KdjResult kdj = quantIndicatorService.calculateKdj(highs, lows, closes, 9, 3, 3);
        Assertions.assertNotNull(kdj);
        Assertions.assertEquals(20, kdj.getK().size());
        Assertions.assertEquals(20, kdj.getD().size());
        Assertions.assertEquals(20, kdj.getJ().size());

        // 验证 J = 3K - 2D
        BigDecimal k = kdj.getK().get(15);
        BigDecimal d = kdj.getD().get(15);
        BigDecimal j = kdj.getJ().get(15);
        BigDecimal expectedJ = k.multiply(new BigDecimal("3")).subtract(d.multiply(new BigDecimal("2"))).setScale(2, java.math.RoundingMode.HALF_UP);
        Assertions.assertEquals(expectedJ, j);
    }

    @Autowired
    private com.stock.service.CopilotService copilotService;

    @Test
    void testKlineChartWithQuantIndicators() {
        KlineChartDTO chart = quoteService.fetchKlineChart("159242");
        Assertions.assertNotNull(chart);
        if (chart.getDates() != null && !chart.getDates().isEmpty()) {
            System.out.println("日K线指标检查: dates=" + chart.getDates().size()
                    + ", BOLL mid=" + (chart.getBollMid() != null ? chart.getBollMid().size() : 0)
                    + ", MACD bar=" + (chart.getMacdBar() != null ? chart.getMacdBar().size() : 0)
                    + ", KDJ j=" + (chart.getKdjJ() != null ? chart.getKdjJ().size() : 0)
                    + ", Pivot=" + chart.getPivotPoints());
            Assertions.assertNotNull(chart.getBollMid());
            Assertions.assertNotNull(chart.getMacdBar());
            Assertions.assertNotNull(chart.getKdjJ());
            Assertions.assertNotNull(chart.getPivotPoints());
        }
    }

    @Test
    void testPredictNextDayTrend() {
        com.stock.dto.CopilotPredictRequest req = com.stock.dto.CopilotPredictRequest.builder()
                .symbol("159242")
                .build();
        com.stock.dto.CopilotPredictResponse resp = copilotService.predictNextDayTrend(req);
        Assertions.assertNotNull(resp);
        Assertions.assertEquals("159242", resp.getSymbol());
        Assertions.assertNotNull(resp.getTrendVerdict());
        Assertions.assertNotNull(resp.getBullishProbability());
        Assertions.assertTrue(resp.getBullishProbability() >= 0 && resp.getBullishProbability() <= 100);
        Assertions.assertNotNull(resp.getAnalysisReport());
        Assertions.assertTrue(resp.getAnalysisReport().contains("明日趋势定调"));
        System.out.println("AI 次日走势推演结果:");
        System.out.println("定调: " + resp.getTrendVerdict() + " (" + resp.getBullishProbability() + "%)");
        System.out.println("持仓均价: ¥" + resp.getHoldCostPrice() + ", 摊薄保本价: ¥" + resp.getDilutedCostPrice());
        System.out.println("模型/引擎: " + resp.getModelUsed());
        System.out.println("推演详报前 200 字: \n" + resp.getAnalysisReport().substring(0, Math.min(200, resp.getAnalysisReport().length())));
    }
}

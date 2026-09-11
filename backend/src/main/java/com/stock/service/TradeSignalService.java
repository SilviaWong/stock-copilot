package com.stock.service;

import com.stock.dto.MarketOverviewDTO;
import com.stock.entity.Position;
import com.stock.entity.TransactionRecord;
import com.stock.vo.PositionVO;
import com.stock.vo.TradeSignalVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 股票与 ETF 智能交易信号推荐服务 (持仓买卖节奏与网格做 T 指导引擎，融合大盘风控过滤器)
 */
@Service
public class TradeSignalService {

    /**
     * 兼容接口：无大盘上下文的旧版调用
     */
    public TradeSignalVO evaluateSignal(Position position, PositionVO vo, List<TransactionRecord> records) {
        return evaluateSignal(position, vo, records, null);
    }

    /**
     * 为指定持仓计算实时操作推荐信号 (引入大盘多空环境与相对强弱风控过滤器)
     *
     * @param position       原始持仓实体
     * @param vo             丰富后的持仓展示对象 (含实时现价、涨跌幅、摊薄成本、相对大盘强弱)
     * @param records        该标的历史交易流水 (用于定位近期买入与做 T 点位)
     * @param marketOverview 全市场宏观大盘指数与情绪晴雨表
     * @return 智能决策信号
     */
    public TradeSignalVO evaluateSignal(Position position, PositionVO vo, List<TransactionRecord> records, MarketOverviewDTO marketOverview) {

        int holdQuantity = position.getHoldQuantity() != null ? position.getHoldQuantity() : 0;
        BigDecimal costPrice = position.getCostPrice() != null ? position.getCostPrice() : BigDecimal.ZERO;
        BigDecimal totalCost = position.getTotalCost() != null ? position.getTotalCost() : BigDecimal.ZERO;
        BigDecimal currentPrice = vo.getCurrentPrice() != null ? vo.getCurrentPrice() : costPrice;
        BigDecimal changePercent = vo.getChangePercent() != null ? vo.getChangePercent() : BigDecimal.ZERO;

        // 1. 已清仓或空仓标的
        if (holdQuantity <= 0) {
            return TradeSignalVO.builder()
                    .signalType("HOLD")
                    .title("⚪ 空仓观望")
                    .description("当前未持有该标的份额，建议等待估值底部或明确右侧趋势再行分批建仓。")
                    .suggestedPrice(currentPrice)
                    .suggestedQuantity(0)
                    .level("info")
                    .build();
        }

        // 2. 现价异常降级保护
        if (currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return TradeSignalVO.builder()
                    .signalType("HOLD")
                    .title("⚪ 保持现状")
                    .description("实时行情拉取中，维持现有仓位，请稍后刷新查看。")
                    .level("info")
                    .build();
        }

        // 3. 优先检查用户设置的硬止盈与硬止损预警
        if (position.getTargetTakeProfit() != null && currentPrice.compareTo(position.getTargetTakeProfit()) >= 0) {
            int suggestedSellQty = calculateSuggestedQty(holdQuantity);
            BigDecimal estProfit = currentPrice.subtract(costPrice)
                    .multiply(BigDecimal.valueOf(suggestedSellQty))
                    .setScale(2, RoundingMode.HALF_UP);

            return TradeSignalVO.builder()
                    .signalType("SELL")
                    .title("🎉 触及止盈")
                    .description(String.format("现价 ¥%.3f 已达到目标止盈线 ¥%.3f！建议分批减仓 %d 股锁定落袋利润 (预计落袋 ¥%.2f)。",
                            currentPrice, position.getTargetTakeProfit(), suggestedSellQty, estProfit))
                    .suggestedPrice(currentPrice)
                    .suggestedQuantity(suggestedSellQty)
                    .estimatedProfit(estProfit)
                    .level("warning")
                    .build();
        }

        if (position.getTargetStopLoss() != null && currentPrice.compareTo(position.getTargetStopLoss()) <= 0) {
            int suggestedSellQty = calculateSuggestedQty(holdQuantity);
            return TradeSignalVO.builder()
                    .signalType("ALERT")
                    .title("⚠️ 跌破止损")
                    .description(String.format("现价 ¥%.3f 已跌破设定的防守止损线 ¥%.3f！请注意控制下行风险，建议适度减仓或坚决执行纪律。",
                            currentPrice, position.getTargetStopLoss()))
                    .suggestedPrice(currentPrice)
                    .suggestedQuantity(suggestedSellQty)
                    .level("danger")
                    .build();
        }

        // 4. 定位最近一笔买入交易
        Optional<TransactionRecord> lastBuyOpt = records == null ? Optional.empty() : records.stream()
                .filter(r -> "BUY".equalsIgnoreCase(r.getAction()))
                .max(Comparator.comparing(TransactionRecord::getTradeTime, Comparator.nullsLast(Comparator.naturalOrder())));

        BigDecimal referenceBuyPrice = lastBuyOpt.map(TransactionRecord::getPrice).orElse(costPrice);
        if (referenceBuyPrice.compareTo(BigDecimal.ZERO) <= 0) {
            referenceBuyPrice = costPrice;
        }

        // 涨跌幅度计算
        // 相比最近加仓点的涨跌幅 (%)
        double reboundFromLastBuy = 0.0;
        if (referenceBuyPrice.compareTo(BigDecimal.ZERO) > 0) {
            reboundFromLastBuy = currentPrice.subtract(referenceBuyPrice)
                    .divide(referenceBuyPrice, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();
        }

        // 相比持仓均价的累计浮动盈亏率 (%)
        double pnlRate = vo.getFloatingPnlRate() != null ? vo.getFloatingPnlRate().doubleValue() : 0.0;
        int defaultQty = calculateSuggestedQty(holdQuantity);

        // 5. 规则决策引擎判断 (融合大盘多空环境与相对强弱风控过滤器)
        boolean isMarketWeak = (vo.getBenchmarkChangePercent() != null && vo.getBenchmarkChangePercent().doubleValue() <= -1.5)
                || (marketOverview != null && "PANIC".equalsIgnoreCase(marketOverview.getSentimentLevel()));
        boolean isMarketSurging = marketOverview != null && "FEVER".equalsIgnoreCase(marketOverview.getSentimentLevel());
        boolean isStockOutperforming = vo.getRelativeStrength() != null && vo.getRelativeStrength().doubleValue() >= 1.5;

        // 5.1 【建议减仓做 T / 止盈锁定】
        // 条件：相比上次买点反弹 >= 4.0%，或者持仓累计浮盈 >= 6.0% 且涨势放缓
        if (reboundFromLastBuy >= 4.0 || (pnlRate >= 6.0 && changePercent.doubleValue() < 0)) {
            BigDecimal estProfit = currentPrice.subtract(costPrice)
                    .multiply(BigDecimal.valueOf(defaultQty))
                    .setScale(2, RoundingMode.HALF_UP);

            String reason = reboundFromLastBuy >= 4.0
                    ? String.format("较前次买入已反弹达标 (+%.1f%%)", reboundFromLastBuy)
                    : String.format("累计持仓浮盈丰厚 (+%.1f%%)", pnlRate);

            String title = "🔴 建议减仓做T";
            String extraTip = "";
            if (isStockOutperforming && vo.getBenchmarkChangePercent() != null && vo.getBenchmarkChangePercent().doubleValue() < 0) {
                title = "🔴 逆势走强做T";
                extraTip = String.format(" [主力逆势拉升: 跑赢大盘 +%s%%，谨防尾盘受大盘拖累冲高回落，高抛落袋胜率极佳]", vo.getRelativeStrength());
            } else if (isMarketSurging) {
                title = "🔴 顺风减仓做T";
                extraTip = " [全市场交投过热，主力资金加速换手，适宜分批止盈兑现]";
            }

            return TradeSignalVO.builder()
                    .signalType("SELL")
                    .title(title)
                    .description(String.format("%s，触及高抛止盈区间%s。建议在 ¥%.3f 附近卖出 %d 股锁定波段利润，预计落袋收益 ¥%.2f。",
                            reason, extraTip, currentPrice, defaultQty, estProfit))
                    .suggestedPrice(currentPrice)
                    .suggestedQuantity(defaultQty)
                    .estimatedProfit(estProfit)
                    .level("warning")
                    .build();
        }

        // 5.2 【建议逢低加仓 / 摊薄做 T】
        // 条件：较前次买点回撤 <= -3.0%，或累计浮亏 <= -3.5%，或日内急跌超 2%
        if (reboundFromLastBuy <= -3.0 || pnlRate <= -3.5 || changePercent.doubleValue() <= -2.0) {
            int actualBuyQty = defaultQty;
            String title = "🟢 建议逢低加仓";
            String marketAlert = "";

            if (isMarketWeak) {
                // 大盘破位重挫时，启动防踩踏过滤器，减半建议买入股数
                actualBuyQty = Math.max(100, (defaultQty / 200) * 100);
                title = "🟢 逆势分批低吸";
                marketAlert = String.format(" ⚠️【大盘逆风风控提示】：当前基准大盘 (%s %s%%) 明显走弱，此买点属左侧逆风博弈，已自动将加仓手数调减为 %d 股以控制下行敞口，严禁单笔重仓追击。",
                        vo.getBenchmarkName() != null ? vo.getBenchmarkName() : "大盘",
                        vo.getBenchmarkChangePercent() != null ? vo.getBenchmarkChangePercent() : "0.00",
                        actualBuyQty);
            }

            BigDecimal buyAmount = currentPrice.multiply(BigDecimal.valueOf(actualBuyQty));
            BigDecimal newTotalCost = totalCost.add(buyAmount);
            BigDecimal newCostPrice = newTotalCost.divide(BigDecimal.valueOf(holdQuantity + actualBuyQty), 4, RoundingMode.HALF_UP);

            String reason = reboundFromLastBuy <= -3.0
                    ? String.format("较前次买点已回调 %.1f%%", Math.abs(reboundFromLastBuy))
                    : (changePercent.doubleValue() <= -2.0 ? String.format("今日盘中急跌 %.2f%%", changePercent.doubleValue()) : String.format("当前持仓微亏 %.1f%%", Math.abs(pnlRate)));

            return TradeSignalVO.builder()
                    .signalType("BUY")
                    .title(title)
                    .description(String.format("%s，触及网格低吸区间。建议在 ¥%.3f 附近加仓 %d 股，加仓后持仓均价预计降至 ¥%.4f。%s",
                            reason, currentPrice, actualBuyQty, newCostPrice, marketAlert))
                    .suggestedPrice(currentPrice)
                    .suggestedQuantity(actualBuyQty)
                    .estimatedNewCost(newCostPrice)
                    .level("success")
                    .build();
        }

        // 5.3 【持股观望 / 耐心守候】
        BigDecimal nextBuyTrigger = currentPrice.multiply(BigDecimal.valueOf(0.97)).setScale(3, RoundingMode.HALF_UP);
        BigDecimal nextSellTrigger = currentPrice.multiply(BigDecimal.valueOf(1.04)).setScale(3, RoundingMode.HALF_UP);

        String holdTitle = "⚪ 持股观望";
        String rsRemark = "";
        if (vo.getRelativeStrength() != null) {
            if (vo.getRelativeStrength().doubleValue() >= 1.0) {
                holdTitle = "⚪ 偏强持股观望";
                rsRemark = String.format(" (今日表现强于大盘 %s，主力护盘韧性良好)", vo.getBenchmarkName() != null ? vo.getBenchmarkName() : "大盘");
            } else if (vo.getRelativeStrength().doubleValue() <= -1.0) {
                holdTitle = "⚪ 偏弱持股防守";
                rsRemark = String.format(" (今日走势跑输大盘 %s，上方抛压较重，耐心等待企稳)", vo.getBenchmarkName() != null ? vo.getBenchmarkName() : "大盘");
            }
        }

        return TradeSignalVO.builder()
                .signalType("HOLD")
                .title(holdTitle)
                .description(String.format("价格处于成本安全垫区间内正常波动%s，无需频繁操作。建议耐心持股，下档加仓位约 ¥%.3f (-3%%)，上档减仓位约 ¥%.3f (+4%%)。",
                        rsRemark, nextBuyTrigger, nextSellTrigger))
                .suggestedPrice(nextBuyTrigger)
                .suggestedQuantity(defaultQty)
                .level("info")
                .build();

    }

    /**
     * 根据当前持仓规模自适应计算单次建议买卖手数 (以100股为最小单位，一般占底仓的 3% ~ 10%)
     */
    private int calculateSuggestedQty(int holdQuantity) {
        if (holdQuantity <= 1000) {
            return 100;
        }
        if (holdQuantity <= 5000) {
            return 500;
        }
        if (holdQuantity <= 20000) {
            return 1000;
        }
        if (holdQuantity <= 50000) {
            return 2000; // 例如 33,000 股持仓，单次做 T 2,000 股
        }
        return 5000;
    }
}

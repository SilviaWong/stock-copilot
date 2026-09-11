package com.stock.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stock.common.BusinessException;
import com.stock.dto.IndexQuoteDTO;
import com.stock.dto.MarketOverviewDTO;
import com.stock.dto.QuoteDTO;
import com.stock.entity.Position;
import com.stock.entity.TransactionRecord;
import com.stock.mapper.PositionMapper;
import com.stock.mapper.TransactionRecordMapper;
import com.stock.vo.AccountSummaryVO;
import com.stock.vo.PositionVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PositionService {

    @Autowired
    private PositionMapper positionMapper;

    @Autowired
    private TransactionRecordMapper transactionRecordMapper;

    @Autowired
    private QuoteService quoteService;

    @Autowired
    private TradeSignalService tradeSignalService;

    /**
     * 查询持仓列表并注入实时行情、券商摊薄保本成本、相对大盘强弱及做T建议
     *
     * @param onlyHolding 是否仅展示当前仍持有的标的 (hold_quantity > 0)
     */
    public List<PositionVO> listPositions(Boolean onlyHolding) {
        LambdaQueryWrapper<Position> wrapper = new LambdaQueryWrapper<>();
        if (Boolean.TRUE.equals(onlyHolding)) {
            wrapper.gt(Position::getHoldQuantity, 0);
        }
        wrapper.orderByDesc(Position::getTotalCost);

        List<Position> list = positionMapper.selectList(wrapper);
        if (list.isEmpty()) {
            return Collections.emptyList();
        }

        // 提取全市场大盘全景
        MarketOverviewDTO marketOverview = quoteService.getMarketOverview();
        Map<String, IndexQuoteDTO> indexMap = (marketOverview != null && marketOverview.getIndices() != null)
                ? marketOverview.getIndices().stream().collect(Collectors.toMap(IndexQuoteDTO::getSymbol, idx -> idx, (a, b) -> a))
                : Collections.emptyMap();

        // 提取所有持仓标的代码，批量拉取最新实时行情
        List<String> symbols = list.stream()
                .map(Position::getSymbol)
                .distinct()
                .collect(Collectors.toList());

        Map<String, QuoteDTO> quoteMap = quoteService.batchFetchQuotes(symbols);

        // 批量查询相关标的所有历史流水以计算券商摊薄保本成本
        List<TransactionRecord> allRecords = symbols.isEmpty()
                ? Collections.emptyList()
                : transactionRecordMapper.selectList(
                        new LambdaQueryWrapper<TransactionRecord>().in(TransactionRecord::getSymbol, symbols)
                  );
        Map<String, List<TransactionRecord>> recordsBySymbol = allRecords.stream()
                .collect(Collectors.groupingBy(TransactionRecord::getSymbol));

        return list.stream().map(p -> {
            PositionVO vo = new PositionVO();
            BeanUtils.copyProperties(p, vo);

            QuoteDTO quote = quoteMap.get(p.getSymbol());
            int holdQuantity = p.getHoldQuantity() != null ? p.getHoldQuantity() : 0;
            BigDecimal costPrice = p.getCostPrice() != null ? p.getCostPrice() : BigDecimal.ZERO;
            BigDecimal totalCost = p.getTotalCost() != null ? p.getTotalCost() : BigDecimal.ZERO;

            if (quote != null && quote.getCurrentPrice() != null && quote.getCurrentPrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal currentPrice = quote.getCurrentPrice();
                vo.setCurrentPrice(currentPrice);
                vo.setChangePercent(quote.getChangePercent());

                // 今日持仓盈亏波动额 = 今日涨跌额 * 持仓股数
                BigDecimal dailyPnl = quote.getChangeAmount() != null
                        ? quote.getChangeAmount().multiply(BigDecimal.valueOf(holdQuantity)).setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                vo.setDailyPnl(dailyPnl);

                // 当前持仓最新市值 = 实时单价 * 持仓股数
                BigDecimal marketValue = currentPrice.multiply(BigDecimal.valueOf(holdQuantity)).setScale(2, RoundingMode.HALF_UP);
                vo.setMarketValue(marketValue);

                if (holdQuantity > 0) {
                    // 浮动盈亏额 = 最新市值 - 投入总成本
                    BigDecimal floatingPnl = marketValue.subtract(totalCost).setScale(2, RoundingMode.HALF_UP);
                    vo.setFloatingPnl(floatingPnl);

                    // 浮动盈亏率 = (当前价 - 成本价) / 成本价 * 100%
                    if (costPrice.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal pnlRate = currentPrice.subtract(costPrice)
                                .divide(costPrice, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                                .setScale(2, RoundingMode.HALF_UP);
                        vo.setFloatingPnlRate(pnlRate);
                    } else {
                        vo.setFloatingPnlRate(BigDecimal.ZERO);
                    }

                    // 检查是否达到止盈或止损价
                    if (p.getTargetTakeProfit() != null && currentPrice.compareTo(p.getTargetTakeProfit()) >= 0) {
                        vo.setTakeProfitAlert(true);
                    } else {
                        vo.setTakeProfitAlert(false);
                    }

                    if (p.getTargetStopLoss() != null && currentPrice.compareTo(p.getTargetStopLoss()) <= 0) {
                        vo.setStopLossAlert(true);
                    } else {
                        vo.setStopLossAlert(false);
                    }
                } else {
                    // 已清仓
                    vo.setMarketValue(BigDecimal.ZERO);
                    vo.setFloatingPnl(BigDecimal.ZERO);
                    vo.setFloatingPnlRate(BigDecimal.ZERO);
                }
            } else {
                // 降级兜底 (如离线或未收盘拉不到)
                vo.setCurrentPrice(costPrice);
                vo.setMarketValue(totalCost);
                vo.setFloatingPnl(BigDecimal.ZERO);
                vo.setFloatingPnlRate(BigDecimal.ZERO);
                vo.setChangePercent(BigDecimal.ZERO);
                vo.setDailyPnl(BigDecimal.ZERO);
            }

            // 计算券商口径指标 (盈亏摊薄法 / 做T保本价)
            List<TransactionRecord> symbolRecords = recordsBySymbol.getOrDefault(p.getSymbol(), Collections.emptyList());
            BigDecimal totalBuyCash = BigDecimal.ZERO;
            BigDecimal totalSellCash = BigDecimal.ZERO;
            BigDecimal totalFees = BigDecimal.ZERO;

            for (TransactionRecord r : symbolRecords) {
                BigDecimal fee = r.getFee() != null ? r.getFee() : BigDecimal.ZERO;
                totalFees = totalFees.add(fee);
                if ("BUY".equalsIgnoreCase(r.getAction())) {
                    totalBuyCash = totalBuyCash.add(r.getAmount());
                } else if ("SELL".equalsIgnoreCase(r.getAction()) || "DIVIDEND".equalsIgnoreCase(r.getAction())) {
                    totalSellCash = totalSellCash.add(r.getAmount());
                }
            }

            BigDecimal dilutedTotalCost;
            BigDecimal dilutedCostPrice;

            if (symbolRecords.isEmpty()) {
                dilutedTotalCost = totalCost;
                dilutedCostPrice = costPrice;
            } else {
                dilutedTotalCost = totalBuyCash.subtract(totalSellCash).add(totalFees);
                if (holdQuantity > 0) {
                    dilutedCostPrice = dilutedTotalCost.divide(BigDecimal.valueOf(holdQuantity), 4, RoundingMode.HALF_UP);
                } else {
                    dilutedCostPrice = BigDecimal.ZERO;
                }
            }

            vo.setDilutedTotalCost(dilutedTotalCost);
            vo.setDilutedCostPrice(dilutedCostPrice);

            // 标的历史累计总盈亏 = 当前最新市值 - 摊薄总成本 (若已清仓则为 -dilutedTotalCost，即历史净落袋)
            BigDecimal currentMarketVal = vo.getMarketValue() != null ? vo.getMarketValue() : totalCost;
            BigDecimal totalPnl = currentMarketVal.subtract(dilutedTotalCost).setScale(2, RoundingMode.HALF_UP);
            vo.setTotalPnl(totalPnl);

            // 标的累计总收益率
            if (dilutedTotalCost.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal totalPnlRate = totalPnl.divide(dilutedTotalCost, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);
                vo.setTotalPnlRate(totalPnlRate);
            } else {
                vo.setTotalPnlRate(BigDecimal.ZERO);
            }

            // 绑定基准指数并计算相对强弱 (Relative Strength / Alpha)
            QuoteService.BenchmarkInfo bm = quoteService.resolveBenchmark(p.getSymbol());
            vo.setBenchmarkSymbol(bm.getSymbol());
            vo.setBenchmarkName(bm.getName());

            IndexQuoteDTO bmQuote = indexMap.get(bm.getSymbol());
            if (bmQuote != null && bmQuote.getChangePercent() != null) {
                vo.setBenchmarkChangePercent(bmQuote.getChangePercent());
                if (vo.getChangePercent() != null) {
                    BigDecimal rs = vo.getChangePercent().subtract(bmQuote.getChangePercent()).setScale(2, RoundingMode.HALF_UP);
                    vo.setRelativeStrength(rs);

                    double rsVal = rs.doubleValue();
                    if (rsVal >= 1.5) {
                        vo.setRelativeStrengthStatus("🚀 强势领涨");
                        vo.setRelativeStrengthLevel("success");
                    } else if (rsVal >= 0.5) {
                        vo.setRelativeStrengthStatus("🟢 偏强共振");
                        vo.setRelativeStrengthLevel("success");
                    } else if (rsVal > -0.5) {
                        vo.setRelativeStrengthStatus("⚪ 同步大盘");
                        vo.setRelativeStrengthLevel("info");
                    } else if (rsVal > -1.5) {
                        vo.setRelativeStrengthStatus("🟡 偏弱滞涨");
                        vo.setRelativeStrengthLevel("warning");
                    } else {
                        vo.setRelativeStrengthStatus("🔴 逆势走弱");
                        vo.setRelativeStrengthLevel("danger");
                    }
                }
            } else {
                vo.setBenchmarkChangePercent(BigDecimal.ZERO);
                vo.setRelativeStrength(BigDecimal.ZERO);
                vo.setRelativeStrengthStatus("⚪ 待关联基准");
                vo.setRelativeStrengthLevel("info");
            }

            // 智能交易与做T决策推荐信号 (融入大盘风控过滤器)
            vo.setTradeSignal(tradeSignalService.evaluateSignal(p, vo, symbolRecords, marketOverview));

            return vo;
        }).collect(Collectors.toList());

    }

    /**
     * 账户总览看板计算 (融合实时市值与总浮盈)
     */
    public AccountSummaryVO getAccountSummary() {
        // 1. 获取持仓列表计算实时资产
        List<PositionVO> holdingList = listPositions(true);

        BigDecimal totalHoldCost = BigDecimal.ZERO;
        BigDecimal totalMarketValue = BigDecimal.ZERO;
        BigDecimal totalFloatingPnl = BigDecimal.ZERO;
        BigDecimal totalDailyPnl = BigDecimal.ZERO;

        for (PositionVO p : holdingList) {
            if (p.getTotalCost() != null) {
                totalHoldCost = totalHoldCost.add(p.getTotalCost());
            }
            if (p.getMarketValue() != null) {
                totalMarketValue = totalMarketValue.add(p.getMarketValue());
            }
            if (p.getFloatingPnl() != null) {
                totalFloatingPnl = totalFloatingPnl.add(p.getFloatingPnl());
            }
            if (p.getDailyPnl() != null) {
                totalDailyPnl = totalDailyPnl.add(p.getDailyPnl());
            }
        }

        BigDecimal totalFloatingPnlRate = BigDecimal.ZERO;
        if (totalHoldCost.compareTo(BigDecimal.ZERO) > 0) {
            totalFloatingPnlRate = totalFloatingPnl
                    .divide(totalHoldCost, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        // 2. 历史所有流水统计
        List<TransactionRecord> allRecords = transactionRecordMapper.selectList(null);
        int totalTrades = allRecords.size();

        List<TransactionRecord> sellRecords = allRecords.stream()
                .filter(r -> "SELL".equalsIgnoreCase(r.getAction()))
                .collect(Collectors.toList());

        BigDecimal totalRealizedPnl = BigDecimal.ZERO;
        int profitableSells = 0;
        int totalSells = sellRecords.size();

        for (TransactionRecord sell : sellRecords) {
            BigDecimal pnl = sell.getRealizedPnl() != null ? sell.getRealizedPnl() : BigDecimal.ZERO;
            totalRealizedPnl = totalRealizedPnl.add(pnl);
            if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                profitableSells++;
            }
        }

        BigDecimal winRate = BigDecimal.ZERO;
        if (totalSells > 0) {
            winRate = BigDecimal.valueOf(profitableSells)
                    .divide(BigDecimal.valueOf(totalSells), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal totalNetProfit = totalFloatingPnl.add(totalRealizedPnl);

        return AccountSummaryVO.builder()
                .totalHoldCost(totalHoldCost)
                .totalMarketValue(totalMarketValue)
                .totalFloatingPnl(totalFloatingPnl)
                .totalFloatingPnlRate(totalFloatingPnlRate)
                .totalDailyPnl(totalDailyPnl)
                .totalRealizedPnl(totalRealizedPnl)
                .totalNetProfit(totalNetProfit)
                .totalTrades(totalTrades)
                .totalSells(totalSells)
                .profitableSells(profitableSells)
                .winRate(winRate)
                .holdingCount(holdingList.size())
                .build();
    }

    /**
     * 设置/更新目标止盈与止损线
     */
    public void updateTargetPrices(String symbol, BigDecimal targetTakeProfit, BigDecimal targetStopLoss) {
        Position position = positionMapper.selectOne(
                new LambdaQueryWrapper<Position>().eq(Position::getSymbol, symbol)
        );
        if (position == null) {
            throw new BusinessException("未找到标的 [" + symbol + "] 的持仓记录");
        }
        position.setTargetTakeProfit(targetTakeProfit);
        position.setTargetStopLoss(targetStopLoss);
        positionMapper.updateById(position);
    }
}

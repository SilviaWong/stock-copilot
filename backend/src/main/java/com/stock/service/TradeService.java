package com.stock.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stock.common.BusinessException;
import com.stock.dto.TradeRequest;
import com.stock.entity.Position;
import com.stock.entity.StockInfo;
import com.stock.entity.TransactionRecord;
import com.stock.mapper.PositionMapper;
import com.stock.mapper.StockInfoMapper;
import com.stock.mapper.TransactionRecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TradeService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private TransactionRecordMapper transactionRecordMapper;

    @Autowired
    private PositionMapper positionMapper;

    @Autowired
    private StockInfoMapper stockInfoMapper;

    /**
     * 批量导入交易流水记录 (按成交时间升序依次入库并重新校准持仓)
     */
    @Transactional(rollbackFor = Exception.class)
    public List<TransactionRecord> batchRecordTrades(List<TradeRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return Collections.emptyList();
        }

        // 按成交时间升序排序，确保历史流水的先后顺序正确
        requests.sort(Comparator.comparing(
                r -> StringUtils.hasText(r.getTradeTime()) ? r.getTradeTime() : "9999-12-31"
        ));

        List<TransactionRecord> results = new ArrayList<>();
        Set<String> affectedSymbols = new HashSet<>();

        for (TradeRequest req : requests) {
            TransactionRecord record = recordTrade(req);
            results.add(record);
            affectedSymbols.add(record.getSymbol());
        }

        // 对受影响的标的进行全量重算校准
        for (String symbol : affectedSymbols) {
            recalculatePositionBySymbol(symbol);
        }

        return results;
    }

    /**
     * 录入一笔交易 (买入 / 卖出 / 现金分红)
     */
    @Transactional(rollbackFor = Exception.class)
    public TransactionRecord recordTrade(TradeRequest request) {
        validateRequest(request);

        String symbol = request.getSymbol().trim();
        String action = request.getAction().toUpperCase();
        BigDecimal price = request.getPrice();
        Integer quantity = request.getQuantity();
        BigDecimal fee = request.getFee() != null ? request.getFee() : BigDecimal.ZERO;

        // 交易时间默认当前时间
        String tradeTime = StringUtils.hasText(request.getTradeTime())
                ? request.getTradeTime()
                : LocalDateTime.now().format(TIME_FORMATTER);

        // 补全标的名称与市场
        String name = resolveStockName(symbol, request.getName());
        String market = resolveMarket(symbol);

        // 获取或初始化持仓
        Position position = positionMapper.selectOne(
                new LambdaQueryWrapper<Position>().eq(Position::getSymbol, symbol)
        );

        BigDecimal amount;
        BigDecimal realizedPnl = BigDecimal.ZERO;

        if ("BUY".equals(action)) {
            amount = price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
            position = handleBuy(position, symbol, name, market, price, quantity, amount, fee);
        } else if ("SELL".equals(action)) {
            amount = price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
            if (position == null || position.getHoldQuantity() < quantity) {
                int currentHold = position == null ? 0 : position.getHoldQuantity();
                throw new BusinessException(String.format("持仓不足！标的 [%s] 当前持仓为 %d 股/份，无法卖出 %d 股/份",
                        symbol, currentHold, quantity));
            }
            // 计算已实现净盈亏: (卖出单价 - 持仓成本均价) * 卖出数量 - 卖出手续费
            BigDecimal costPrice = position.getCostPrice();
            BigDecimal grossProfit = price.subtract(costPrice).multiply(BigDecimal.valueOf(quantity));
            realizedPnl = grossProfit.subtract(fee).setScale(2, RoundingMode.HALF_UP);

            position = handleSell(position, quantity);
        } else if ("DIVIDEND".equals(action)) {
            // 现金分红直接降低持仓成本
            amount = price != null && quantity != null && quantity > 0
                    ? price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP)
                    : (request.getFee() != null ? request.getFee() : BigDecimal.ZERO); // 如果作为总金额传入

            if (position == null || position.getHoldQuantity() <= 0) {
                throw new BusinessException("该标的当前无持仓，无法记录分红！");
            }
            position = handleDividend(position, amount);
        } else {
            throw new BusinessException("不支持的交易类型: " + action + "，仅支持 BUY、SELL、DIVIDEND");
        }

        // 保存交易事实流水
        TransactionRecord record = TransactionRecord.builder()
                .symbol(symbol)
                .name(name)
                .action(action)
                .price(price)
                .quantity(quantity)
                .amount(amount)
                .fee(fee)
                .realizedPnl(realizedPnl)
                .tradeTime(tradeTime)
                .strategyTag(request.getStrategyTag())
                .notes(request.getNotes())
                .createdAt(LocalDateTime.now().format(TIME_FORMATTER))
                .build();

        transactionRecordMapper.insert(record);

        // 同步确保 stock_info 字典存在该标的
        ensureStockInfoExists(symbol, name, market);

        return record;
    }

    /**
     * 买入/加仓计算 (加权平均摊薄成本)
     */
    private Position handleBuy(Position position, String symbol, String name, String market,
                               BigDecimal price, Integer quantity, BigDecimal amount, BigDecimal fee) {
        String now = LocalDateTime.now().format(TIME_FORMATTER);
        if (position == null) {
            // 首次建仓
            BigDecimal totalCost = amount.add(fee);
            BigDecimal costPrice = totalCost.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);

            position = Position.builder()
                    .symbol(symbol)
                    .name(name)
                    .market(market)
                    .holdQuantity(quantity)
                    .costPrice(costPrice)
                    .totalCost(totalCost)
                    .updatedAt(now)
                    .build();
            positionMapper.insert(position);
        } else {
            // 加仓摊薄
            int oldQuantity = position.getHoldQuantity() != null ? position.getHoldQuantity() : 0;
            BigDecimal oldCost = position.getTotalCost() != null ? position.getTotalCost() : BigDecimal.ZERO;

            int newQuantity = oldQuantity + quantity;
            BigDecimal newTotalCost = oldCost.add(amount).add(fee);
            BigDecimal newCostPrice = newTotalCost.divide(BigDecimal.valueOf(newQuantity), 4, RoundingMode.HALF_UP);

            position.setName(name);
            position.setMarket(market);
            position.setHoldQuantity(newQuantity);
            position.setCostPrice(newCostPrice);
            position.setTotalCost(newTotalCost);
            position.setUpdatedAt(now);
            positionMapper.updateById(position);
        }
        return position;
    }

    /**
     * 卖出/减仓计算 (保本均价不变，按比例减少总成本)
     */
    private Position handleSell(Position position, Integer sellQuantity) {
        String now = LocalDateTime.now().format(TIME_FORMATTER);
        int remainingQuantity = position.getHoldQuantity() - sellQuantity;

        if (remainingQuantity == 0) {
            // 全部清仓
            position.setHoldQuantity(0);
            position.setCostPrice(BigDecimal.ZERO);
            position.setTotalCost(BigDecimal.ZERO);
        } else {
            // 部分减仓: 成本单价维持不变，剩余总成本按比例扣减
            BigDecimal newTotalCost = position.getCostPrice()
                    .multiply(BigDecimal.valueOf(remainingQuantity))
                    .setScale(2, RoundingMode.HALF_UP);
            position.setHoldQuantity(remainingQuantity);
            position.setTotalCost(newTotalCost);
        }
        position.setUpdatedAt(now);
        positionMapper.updateById(position);
        return position;
    }

    /**
     * 分红冲减成本
     */
    private Position handleDividend(Position position, BigDecimal dividendAmount) {
        String now = LocalDateTime.now().format(TIME_FORMATTER);
        BigDecimal newTotalCost = position.getTotalCost().subtract(dividendAmount);
        BigDecimal newCostPrice = newTotalCost.divide(BigDecimal.valueOf(position.getHoldQuantity()), 4, RoundingMode.HALF_UP);

        position.setTotalCost(newTotalCost);
        position.setCostPrice(newCostPrice);
        position.setUpdatedAt(now);
        positionMapper.updateById(position);
        return position;
    }

    /**
     * 删除单笔流水，并根据历史所有流水重新回放计算该标的持仓状态（保证账目绝对自洽准确）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteTransaction(Long id) {
        TransactionRecord record = transactionRecordMapper.selectById(id);
        if (record == null) {
            throw new BusinessException("交易记录不存在");
        }
        String symbol = record.getSymbol();
        transactionRecordMapper.deleteById(id);

        // 重新回放重算该标的的持仓
        recalculatePositionBySymbol(symbol);
    }

    /**
     * 根据交易流水全量重算指定标的的持仓与已实现盈亏 (按成交时间历史回放，确保时序与盈亏绝对自洽)
     */
    public void recalculatePositionBySymbol(String symbol) {
        List<TransactionRecord> list = transactionRecordMapper.selectList(
                new LambdaQueryWrapper<TransactionRecord>()
                        .eq(TransactionRecord::getSymbol, symbol)
                        .orderByAsc(TransactionRecord::getTradeTime)
                        .orderByAsc(TransactionRecord::getId)
        );

        Position position = positionMapper.selectOne(
                new LambdaQueryWrapper<Position>().eq(Position::getSymbol, symbol)
        );

        if (list.isEmpty()) {
            if (position != null) {
                positionMapper.deleteById(position.getId());
            }
            return;
        }

        int currentHold = 0;
        BigDecimal currentTotalCost = BigDecimal.ZERO;
        BigDecimal currentCostPrice = BigDecimal.ZERO;
        String name = list.get(0).getName();
        String market = resolveMarket(symbol);

        for (TransactionRecord r : list) {
            BigDecimal fee = r.getFee() != null ? r.getFee() : BigDecimal.ZERO;
            if ("BUY".equalsIgnoreCase(r.getAction())) {
                currentHold += r.getQuantity();
                currentTotalCost = currentTotalCost.add(r.getAmount()).add(fee);
                currentCostPrice = currentTotalCost.divide(BigDecimal.valueOf(currentHold), 4, RoundingMode.HALF_UP);
                if (r.getRealizedPnl() == null || r.getRealizedPnl().compareTo(BigDecimal.ZERO) != 0) {
                    r.setRealizedPnl(BigDecimal.ZERO);
                    transactionRecordMapper.updateById(r);
                }
            } else if ("SELL".equalsIgnoreCase(r.getAction())) {
                // 核心修复：按历史真实持仓均价计算并持久化已实现净盈亏
                BigDecimal pnl = r.getPrice().subtract(currentCostPrice)
                        .multiply(BigDecimal.valueOf(r.getQuantity()))
                        .subtract(fee)
                        .setScale(2, RoundingMode.HALF_UP);

                if (r.getRealizedPnl() == null || r.getRealizedPnl().compareTo(pnl) != 0) {
                    r.setRealizedPnl(pnl);
                    transactionRecordMapper.updateById(r);
                }

                currentHold -= r.getQuantity();
                if (currentHold <= 0) {
                    currentHold = 0;
                    currentTotalCost = BigDecimal.ZERO;
                    currentCostPrice = BigDecimal.ZERO;
                } else {
                    currentTotalCost = currentCostPrice.multiply(BigDecimal.valueOf(currentHold)).setScale(2, RoundingMode.HALF_UP);
                }
            } else if ("DIVIDEND".equalsIgnoreCase(r.getAction())) {
                if (currentHold > 0) {
                    currentTotalCost = currentTotalCost.subtract(r.getAmount());
                    currentCostPrice = currentTotalCost.divide(BigDecimal.valueOf(currentHold), 4, RoundingMode.HALF_UP);
                }
            }
        }

        String now = LocalDateTime.now().format(TIME_FORMATTER);
        if (position == null) {
            position = Position.builder()
                    .symbol(symbol)
                    .name(name)
                    .market(market)
                    .holdQuantity(currentHold)
                    .costPrice(currentCostPrice)
                    .totalCost(currentTotalCost)
                    .updatedAt(now)
                    .build();
            positionMapper.insert(position);
        } else {
            position.setHoldQuantity(currentHold);
            position.setCostPrice(currentCostPrice);
            position.setTotalCost(currentTotalCost);
            position.setUpdatedAt(now);
            positionMapper.updateById(position);
        }
    }

    /**
     * 全量自动校验并重算所有标的持仓与已实现盈亏 (按交易时序真实回放)
     */
    public void recalculateAllPositions() {
        List<TransactionRecord> list = transactionRecordMapper.selectList(null);
        if (list == null || list.isEmpty()) return;
        Set<String> symbols = list.stream().map(TransactionRecord::getSymbol).collect(Collectors.toSet());
        for (String symbol : symbols) {
            recalculatePositionBySymbol(symbol);
        }
    }

    @jakarta.annotation.PostConstruct
    public void initAndAutoCalibrate() {
        try {
            recalculateAllPositions();
        } catch (Exception e) {
            // 忽略启动初期日志
        }
    }

    /**
     * 直接初始化或手动修改已有持仓 (免去逐笔录入历史流水的麻烦)
     */
    @Transactional(rollbackFor = Exception.class)
    public Position initOrUpdateHolding(com.stock.dto.PositionInitRequest request) {
        if (!StringUtils.hasText(request.getSymbol())) {
            throw new BusinessException("标的代码不能为空！");
        }
        String symbol = request.getSymbol().trim();
        int holdQuantity = request.getHoldQuantity() != null ? request.getHoldQuantity() : 0;
        BigDecimal costPrice = request.getCostPrice() != null ? request.getCostPrice() : BigDecimal.ZERO;
        BigDecimal totalCost = costPrice.multiply(BigDecimal.valueOf(holdQuantity)).setScale(2, RoundingMode.HALF_UP);
        String name = resolveStockName(symbol, request.getName());
        String market = resolveMarket(symbol);
        String now = LocalDateTime.now().format(TIME_FORMATTER);

        Position position = positionMapper.selectOne(
                new LambdaQueryWrapper<Position>().eq(Position::getSymbol, symbol)
        );

        if (position == null) {
            position = Position.builder()
                    .symbol(symbol)
                    .name(name)
                    .market(market)
                    .holdQuantity(holdQuantity)
                    .costPrice(costPrice)
                    .totalCost(totalCost)
                    .targetTakeProfit(request.getTargetTakeProfit())
                    .targetStopLoss(request.getTargetStopLoss())
                    .updatedAt(now)
                    .build();
            positionMapper.insert(position);

            // 自动同步一条初始底仓流水事实记录
            if (holdQuantity > 0) {
                TransactionRecord initialRecord = TransactionRecord.builder()
                        .symbol(symbol)
                        .name(name)
                        .action("BUY")
                        .price(costPrice)
                        .quantity(holdQuantity)
                        .amount(totalCost)
                        .fee(BigDecimal.ZERO)
                        .realizedPnl(BigDecimal.ZERO)
                        .tradeTime(now)
                        .strategyTag("初始底仓建档")
                        .notes(StringUtils.hasText(request.getNotes()) ? request.getNotes() : "手动录入已有底仓")
                        .createdAt(now)
                        .build();
                transactionRecordMapper.insert(initialRecord);
            }
        } else {
            // 就地修改已存在的持仓
            position.setName(name);
            position.setMarket(market);
            position.setHoldQuantity(holdQuantity);
            position.setCostPrice(costPrice);
            position.setTotalCost(totalCost);
            if (request.getTargetTakeProfit() != null) position.setTargetTakeProfit(request.getTargetTakeProfit());
            if (request.getTargetStopLoss() != null) position.setTargetStopLoss(request.getTargetStopLoss());
            position.setUpdatedAt(now);
            positionMapper.updateById(position);
        }

        ensureStockInfoExists(symbol, name, market);
        return position;
    }

    /**
     * 修改历史单笔交易流水，并自动全量重算持仓
     */
    @Transactional(rollbackFor = Exception.class)
    public TransactionRecord updateTransaction(Long id, TradeRequest request) {
        TransactionRecord record = transactionRecordMapper.selectById(id);
        if (record == null) {
            throw new BusinessException("交易记录不存在");
        }

        String symbol = record.getSymbol();
        if (StringUtils.hasText(request.getName())) record.setName(request.getName());
        if (StringUtils.hasText(request.getAction())) record.setAction(request.getAction().toUpperCase());
        if (request.getPrice() != null) record.setPrice(request.getPrice());
        if (request.getQuantity() != null) record.setQuantity(request.getQuantity());
        if (record.getPrice() != null && record.getQuantity() != null) {
            record.setAmount(record.getPrice().multiply(BigDecimal.valueOf(record.getQuantity())).setScale(2, RoundingMode.HALF_UP));
        }
        if (request.getFee() != null) record.setFee(request.getFee());
        if (StringUtils.hasText(request.getTradeTime())) record.setTradeTime(request.getTradeTime());
        if (StringUtils.hasText(request.getStrategyTag())) record.setStrategyTag(request.getStrategyTag());
        if (request.getNotes() != null) record.setNotes(request.getNotes());

        transactionRecordMapper.updateById(record);

        // 自动根据全量流水重新校准持仓
        recalculatePositionBySymbol(symbol);
        return record;
    }

    /**
     * 删除某个标的的全部持仓与历史流水
     */
    @Transactional(rollbackFor = Exception.class)
    public void deletePositionAndRecords(String symbol) {
        transactionRecordMapper.delete(
                new LambdaQueryWrapper<TransactionRecord>().eq(TransactionRecord::getSymbol, symbol)
        );
        positionMapper.delete(
                new LambdaQueryWrapper<Position>().eq(Position::getSymbol, symbol)
        );
    }

    /**
     * 一键重置清空全部交易与持仓数据 (恢复至全新就绪状态)
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetAllData() {
        transactionRecordMapper.delete(null);
        positionMapper.delete(null);
    }

    private void validateRequest(TradeRequest request) {
        if (!StringUtils.hasText(request.getSymbol())) {
            throw new BusinessException("标的代码不能为空！");
        }
        if (!StringUtils.hasText(request.getAction())) {
            throw new BusinessException("交易动作不能为空！");
        }
        if (request.getPrice() == null || request.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("成交价格必须大于 0！");
        }
        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            throw new BusinessException("成交数量必须大于 0！");
        }
    }

    private String resolveStockName(String symbol, String providedName) {
        if (StringUtils.hasText(providedName)) {
            return providedName.trim();
        }
        StockInfo info = stockInfoMapper.selectOne(
                new LambdaQueryWrapper<StockInfo>().eq(StockInfo::getSymbol, symbol)
        );
        return info != null ? info.getName() : symbol;
    }

    private String resolveMarket(String symbol) {
        if (symbol.startsWith("6") || symbol.startsWith("5")) {
            return "SH";
        } else if (symbol.startsWith("0") || symbol.startsWith("3") || symbol.startsWith("1")) {
            return "SZ";
        } else if (symbol.startsWith("8") || symbol.startsWith("4") || symbol.startsWith("9")) {
            return "BJ";
        }
        return "SH";
    }

    private void ensureStockInfoExists(String symbol, String name, String market) {
        StockInfo existing = stockInfoMapper.selectOne(
                new LambdaQueryWrapper<StockInfo>().eq(StockInfo::getSymbol, symbol)
        );
        if (existing == null) {
            String category = (symbol.startsWith("51") || symbol.startsWith("15") || symbol.startsWith("58")) ? "ETF" : "STOCK";
            StockInfo newStock = StockInfo.builder()
                    .symbol(symbol)
                    .name(name)
                    .market(market)
                    .category(category)
                    .isFavorite(0)
                    .createdAt(LocalDateTime.now().format(TIME_FORMATTER))
                    .build();
            stockInfoMapper.insert(newStock);
        }
    }
}

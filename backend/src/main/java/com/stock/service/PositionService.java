package com.stock.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stock.common.BusinessException;
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
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PositionService {

    @Autowired
    private PositionMapper positionMapper;

    @Autowired
    private TransactionRecordMapper transactionRecordMapper;

    /**
     * 查询持仓列表
     * @param onlyHolding 是否仅展示当前仍持有的标的 (hold_quantity > 0)
     */
    public List<PositionVO> listPositions(Boolean onlyHolding) {
        LambdaQueryWrapper<Position> wrapper = new LambdaQueryWrapper<>();
        if (Boolean.TRUE.equals(onlyHolding)) {
            wrapper.gt(Position::getHoldQuantity, 0);
        }
        wrapper.orderByDesc(Position::getTotalCost);

        List<Position> list = positionMapper.selectList(wrapper);
        return list.stream().map(p -> {
            PositionVO vo = new PositionVO();
            BeanUtils.copyProperties(p, vo);
            // 预留当前市值和浮动盈亏（待接入实时行情时赋值）
            vo.setCurrentPrice(p.getCostPrice()); // 默认先以成本价作为参考
            vo.setMarketValue(p.getTotalCost());
            vo.setFloatingPnl(BigDecimal.ZERO);
            vo.setFloatingPnlRate(BigDecimal.ZERO);
            return vo;
        }).collect(Collectors.toList());
    }

    /**
     * 账户总览看板计算
     */
    public AccountSummaryVO getAccountSummary() {
        // 1. 当前持仓总投入
        List<Position> holdingPositions = positionMapper.selectList(
                new LambdaQueryWrapper<Position>().gt(Position::getHoldQuantity, 0)
        );
        BigDecimal totalHoldCost = holdingPositions.stream()
                .map(p -> p.getTotalCost() != null ? p.getTotalCost() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 2. 历史所有流水统计
        List<TransactionRecord> allRecords = transactionRecordMapper.selectList(null);
        int totalTrades = allRecords.size();

        // 统计所有卖出操作的盈亏
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

        return AccountSummaryVO.builder()
                .totalHoldCost(totalHoldCost)
                .totalRealizedPnl(totalRealizedPnl)
                .totalTrades(totalTrades)
                .totalSells(totalSells)
                .profitableSells(profitableSells)
                .winRate(winRate)
                .holdingCount(holdingPositions.size())
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

package com.stock.controller;

import com.stock.common.Result;
import com.stock.service.PositionService;
import com.stock.vo.AccountSummaryVO;
import com.stock.vo.PositionVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/position")
@CrossOrigin
public class PositionController {

    @Autowired
    private PositionService positionService;

    /**
     * 获取持仓列表 (默认只看持仓中 hold_quantity > 0)
     */
    @GetMapping("/list")
    public Result<List<PositionVO>> listPositions(
            @RequestParam(value = "onlyHolding", required = false, defaultValue = "true") Boolean onlyHolding) {
        return Result.success(positionService.listPositions(onlyHolding));
    }

    /**
     * 获取账户资产总览 (投入本金、落袋收益、胜率等)
     */
    @GetMapping("/summary")
    public Result<AccountSummaryVO> getSummary() {
        return Result.success(positionService.getAccountSummary());
    }

    @Autowired
    private com.stock.service.TradeService tradeService;

    /**
     * 直接录入已有底仓或调整持仓 (免去翻看过去流水)
     */
    @PostMapping("/init")
    public Result<com.stock.entity.Position> initHolding(@RequestBody com.stock.dto.PositionInitRequest request) {
        com.stock.entity.Position pos = tradeService.initOrUpdateHolding(request);
        return Result.success(pos);
    }

    /**
     * 删除标的的所有持仓与历史流水
     */
    @DeleteMapping("/{symbol}")
    public Result<Void> deletePosition(@PathVariable("symbol") String symbol) {
        tradeService.deletePositionAndRecords(symbol);
        return Result.success();
    }

    /**
     * 一键重置清空全部测试/历史数据 (恢复至全新状态)
     */
    @PostMapping("/reset")
    public Result<Void> resetAllData() {
        tradeService.resetAllData();
        return Result.success();
    }

    /**
     * 设置标的的目标止盈价与止损价
     */
    @PutMapping("/targets")
    public Result<Void> updateTargets(@RequestBody Map<String, Object> payload) {
        String symbol = (String) payload.get("symbol");
        BigDecimal takeProfit = payload.get("targetTakeProfit") != null 
                ? new BigDecimal(payload.get("targetTakeProfit").toString()) : null;
        BigDecimal stopLoss = payload.get("targetStopLoss") != null 
                ? new BigDecimal(payload.get("targetStopLoss").toString()) : null;

        positionService.updateTargetPrices(symbol, takeProfit, stopLoss);
        return Result.success();
    }
}

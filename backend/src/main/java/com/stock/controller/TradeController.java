package com.stock.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stock.common.Result;
import com.stock.dto.TradeRequest;
import com.stock.entity.TransactionRecord;
import com.stock.mapper.TransactionRecordMapper;
import com.stock.service.TradeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/trade")
@CrossOrigin // 允许跨域（本地开发方便）
public class TradeController {

    @Autowired
    private TradeService tradeService;

    @Autowired
    private TransactionRecordMapper transactionRecordMapper;

    /**
     * 录入一笔交易记录 (买入/卖出/分红)
     */
    @PostMapping("/record")
    public Result<TransactionRecord> recordTrade(@RequestBody TradeRequest request) {
        TransactionRecord record = tradeService.recordTrade(request);
        return Result.success(record);
    }

    /**
     * 查询交易流水历史 (按时间倒序)
     */
    @GetMapping("/history")
    public Result<List<TransactionRecord>> getHistory(
            @RequestParam(value = "symbol", required = false) String symbol) {
        LambdaQueryWrapper<TransactionRecord> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(symbol)) {
            wrapper.eq(TransactionRecord::getSymbol, symbol.trim());
        }
        wrapper.orderByDesc(TransactionRecord::getTradeTime)
               .orderByDesc(TransactionRecord::getId);
        return Result.success(transactionRecordMapper.selectList(wrapper));
    }

    /**
     * 删除单笔交易流水并重新回放校准持仓
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteRecord(@PathVariable("id") Long id) {
        tradeService.deleteTransaction(id);
        return Result.success();
    }
}

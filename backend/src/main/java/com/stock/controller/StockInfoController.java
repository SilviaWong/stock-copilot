package com.stock.controller;

import com.stock.common.Result;
import com.stock.entity.StockInfo;
import com.stock.service.StockInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stock")
@CrossOrigin
public class StockInfoController {

    @Autowired
    private StockInfoService stockInfoService;

    /**
     * 搜索标的 (支持代码或名称模糊匹配)
     */
    @GetMapping("/search")
    public Result<List<StockInfo>> search(@RequestParam(value = "keyword", required = false) String keyword) {
        return Result.success(stockInfoService.search(keyword));
    }

    /**
     * 切换自选关注状态
     */
    @PostMapping("/favorite/{symbol}")
    public Result<Void> toggleFavorite(@PathVariable("symbol") String symbol) {
        stockInfoService.toggleFavorite(symbol);
        return Result.success();
    }

    /**
     * 手动新增或修改标的信息
     */
    @PostMapping("/save")
    public Result<Void> saveStock(@RequestBody StockInfo stockInfo) {
        stockInfoService.addOrUpdate(stockInfo);
        return Result.success();
    }
}

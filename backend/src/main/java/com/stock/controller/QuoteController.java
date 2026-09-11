package com.stock.controller;

import com.stock.common.Result;
import com.stock.dto.FiveDayChartDTO;
import com.stock.dto.KlineChartDTO;
import com.stock.dto.MarketOverviewDTO;
import com.stock.dto.MinuteChartDTO;
import com.stock.dto.QuoteDTO;
import com.stock.service.QuoteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/quote")
@CrossOrigin
public class QuoteController {

    @Autowired
    private QuoteService quoteService;

    /**
     * 获取全市场宏观大盘指数全景与情绪晴雨表
     */
    @GetMapping("/market-overview")
    public Result<MarketOverviewDTO> getMarketOverview() {
        MarketOverviewDTO overview = quoteService.getMarketOverview();
        return Result.success(overview);
    }

    /**
     * 获取单个标的的实时行情
     */
    @GetMapping("/detail")
    public Result<QuoteDTO> getQuoteDetail(@RequestParam("symbol") String symbol) {

        QuoteDTO quote = quoteService.fetchQuote(symbol);
        return Result.success(quote);
    }

    /**
     * 获取标的当日分时图数据 (含白线现价、黄线均价、成交量、持仓成本生命线、今日打点)
     */
    @GetMapping("/minute")
    public Result<MinuteChartDTO> getMinuteChart(@RequestParam("symbol") String symbol) {
        MinuteChartDTO chart = quoteService.fetchMinuteChart(symbol);
        return Result.success(chart);
    }

    /**
     * 获取标的连续 5 日分时图数据 (扁平连续序列、日期间隔线、持仓成本线)
     */
    @GetMapping("/five-day")
    public Result<FiveDayChartDTO> getFiveDayChart(@RequestParam("symbol") String symbol) {
        FiveDayChartDTO chart = quoteService.fetchFiveDayChart(symbol);
        return Result.success(chart);
    }

    /**
     * 获取标的日 K 线数据 (蜡烛图、MA5/10/20/60均线、持仓买入与保本线、历史买卖打点图钉)
     */
    @GetMapping("/kline")
    public Result<KlineChartDTO> getKlineChart(@RequestParam("symbol") String symbol) {
        KlineChartDTO chart = quoteService.fetchKlineChart(symbol);
        return Result.success(chart);
    }
}


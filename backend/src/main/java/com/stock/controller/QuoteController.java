package com.stock.controller;

import com.stock.common.Result;
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
     * 获取单个标的的实时行情
     */
    @GetMapping("/detail")
    public Result<QuoteDTO> getQuoteDetail(@RequestParam("symbol") String symbol) {
        QuoteDTO quote = quoteService.fetchQuote(symbol);
        return Result.success(quote);
    }
}

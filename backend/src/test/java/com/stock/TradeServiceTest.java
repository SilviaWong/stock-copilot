package com.stock;

import com.stock.common.BusinessException;
import com.stock.dto.TradeRequest;
import com.stock.entity.Position;
import com.stock.entity.TransactionRecord;
import com.stock.mapper.PositionMapper;
import com.stock.mapper.TransactionRecordMapper;
import com.stock.service.PositionService;
import com.stock.service.TradeService;
import com.stock.vo.AccountSummaryVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

@SpringBootTest
public class TradeServiceTest {

    @Autowired
    private TradeService tradeService;

    @Autowired
    private PositionService positionService;

    @Autowired
    private PositionMapper positionMapper;

    @Autowired
    private TransactionRecordMapper transactionRecordMapper;

    @BeforeEach
    void cleanTestData() {
        // 清理测试标的数据
        transactionRecordMapper.delete(null);
        positionMapper.delete(null);
    }

    @Test
    void testFullTradingLifecycle() {
        String testSymbol = "510300";

        // 1. 首次建仓: 10.00 元买入 1000 股，手续费 5 元
        TradeRequest buy1 = new TradeRequest();
        buy1.setSymbol(testSymbol);
        buy1.setName("沪深300ETF");
        buy1.setAction("BUY");
        buy1.setPrice(new BigDecimal("10.0000"));
        buy1.setQuantity(1000);
        buy1.setFee(new BigDecimal("5.00"));
        buy1.setStrategyTag("网格底仓");
        buy1.setNotes("建立第一笔底仓观察");
        tradeService.recordTrade(buy1);

        Position pos1 = positionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                        .eq(Position::getSymbol, testSymbol)
        );
        Assertions.assertNotNull(pos1);
        Assertions.assertEquals(1000, pos1.getHoldQuantity());
        Assertions.assertEquals(0, new BigDecimal("10005.00").compareTo(pos1.getTotalCost()));
        Assertions.assertEquals(0, new BigDecimal("10.0050").compareTo(pos1.getCostPrice()));

        // 2. 加仓摊薄: 跌到 8.00 元补仓 1000 股，手续费 5 元
        TradeRequest buy2 = new TradeRequest();
        buy2.setSymbol(testSymbol);
        buy2.setAction("BUY");
        buy2.setPrice(new BigDecimal("8.0000"));
        buy2.setQuantity(1000);
        buy2.setFee(new BigDecimal("5.00"));
        buy2.setStrategyTag("下跌加仓");
        buy2.setNotes("估值低位加仓摊薄均价");
        tradeService.recordTrade(buy2);

        Position pos2 = positionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                        .eq(Position::getSymbol, testSymbol)
        );
        Assertions.assertEquals(2000, pos2.getHoldQuantity());
        // 10005 + 8005 = 18010.00
        Assertions.assertEquals(0, new BigDecimal("18010.00").compareTo(pos2.getTotalCost()));
        // 18010 / 2000 = 9.0050
        Assertions.assertEquals(0, new BigDecimal("9.0050").compareTo(pos2.getCostPrice()));

        // 3. 部分减仓: 反弹到 11.00 元卖出 1000 股，手续费 5 元
        TradeRequest sell1 = new TradeRequest();
        sell1.setSymbol(testSymbol);
        sell1.setAction("SELL");
        sell1.setPrice(new BigDecimal("11.0000"));
        sell1.setQuantity(1000);
        sell1.setFee(new BigDecimal("5.00"));
        sell1.setStrategyTag("止盈减仓");
        sell1.setNotes("反弹达到预期，卖出一半锁定利润");
        TransactionRecord sellRecord = tradeService.recordTrade(sell1);

        // 验证净赚金额: (11.00 - 9.0050) * 1000 - 5 = 1995 - 5 = 1990.00
        Assertions.assertEquals(0, new BigDecimal("1990.00").compareTo(sellRecord.getRealizedPnl()));

        Position pos3 = positionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                        .eq(Position::getSymbol, testSymbol)
        );
        Assertions.assertEquals(1000, pos3.getHoldQuantity());
        // 成本均价维持 9.0050 不变，剩余总成本按比例减半: 9005.00
        Assertions.assertEquals(0, new BigDecimal("9.0050").compareTo(pos3.getCostPrice()));
        Assertions.assertEquals(0, new BigDecimal("9005.00").compareTo(pos3.getTotalCost()));

        // 4. 验证风控: 尝试超额卖出 2000 股，应抛出异常
        TradeRequest sellOver = new TradeRequest();
        sellOver.setSymbol(testSymbol);
        sellOver.setAction("SELL");
        sellOver.setPrice(new BigDecimal("12.0000"));
        sellOver.setQuantity(2000);
        Assertions.assertThrows(BusinessException.class, () -> tradeService.recordTrade(sellOver));

        // 5. 验证分红: 分红冲减 500 元本金
        TradeRequest div = new TradeRequest();
        div.setSymbol(testSymbol);
        div.setAction("DIVIDEND");
        div.setPrice(new BigDecimal("0.5000"));
        div.setQuantity(1000); // 相当于分红 500 元
        tradeService.recordTrade(div);

        Position pos4 = positionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                        .eq(Position::getSymbol, testSymbol)
        );
        // 9005 - 500 = 8505.00, 新均价 = 8505 / 1000 = 8.5050
        Assertions.assertEquals(0, new BigDecimal("8505.00").compareTo(pos4.getTotalCost()));
        Assertions.assertEquals(0, new BigDecimal("8.5050").compareTo(pos4.getCostPrice()));

        // 6. 验证账户资产看板
        AccountSummaryVO summary = positionService.getAccountSummary();
        Assertions.assertEquals(0, new BigDecimal("8505.00").compareTo(summary.getTotalHoldCost()));
        Assertions.assertEquals(0, new BigDecimal("1990.00").compareTo(summary.getTotalRealizedPnl()));
        Assertions.assertEquals(1, summary.getHoldingCount());
        Assertions.assertEquals(1, summary.getTotalSells());
        Assertions.assertEquals(0, new BigDecimal("100.00").compareTo(summary.getWinRate())); // 1笔卖出且盈利，胜率100%

        System.out.println("====== 全套买入、加仓摊薄、卖出落袋、分红降本、胜率统计单元测试全部通过！ ======");
    }
}

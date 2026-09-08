package com.stock;

import com.stock.entity.StockInfo;
import com.stock.mapper.StockInfoMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
class BackendApplicationTests {

    @Autowired
    private StockInfoMapper stockInfoMapper;

    @Test
    void contextLoads() {
        // 验证数据库连接与自动初始化表数据
        List<StockInfo> list = stockInfoMapper.selectList(null);
        System.out.println("成功从 SQLite 读取到初始标的数据数量: " + list.size());
        list.forEach(item -> System.out.println("标的: " + item.getSymbol() + " - " + item.getName()));
        Assertions.assertTrue(list.size() >= 3, "初始标的数据应至少包含 3 条 ETF 记录");
    }

}

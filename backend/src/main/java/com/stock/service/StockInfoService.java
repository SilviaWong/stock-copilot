package com.stock.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stock.entity.StockInfo;
import com.stock.mapper.StockInfoMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class StockInfoService {

    @Autowired
    private StockInfoMapper stockInfoMapper;

    public List<StockInfo> search(String keyword) {
        LambdaQueryWrapper<StockInfo> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.like(StockInfo::getSymbol, keyword)
                   .or()
                   .like(StockInfo::getName, keyword);
        }
        wrapper.orderByDesc(StockInfo::getIsFavorite)
               .orderByAsc(StockInfo::getSymbol);
        return stockInfoMapper.selectList(wrapper);
    }

    public void toggleFavorite(String symbol) {
        StockInfo info = stockInfoMapper.selectOne(
                new LambdaQueryWrapper<StockInfo>().eq(StockInfo::getSymbol, symbol)
        );
        if (info != null) {
            info.setIsFavorite(info.getIsFavorite() == 1 ? 0 : 1);
            stockInfoMapper.updateById(info);
        }
    }

    public void addOrUpdate(StockInfo stockInfo) {
        StockInfo existing = stockInfoMapper.selectOne(
                new LambdaQueryWrapper<StockInfo>().eq(StockInfo::getSymbol, stockInfo.getSymbol())
        );
        if (existing == null) {
            stockInfo.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            stockInfoMapper.insert(stockInfo);
        } else {
            existing.setName(stockInfo.getName());
            existing.setMarket(stockInfo.getMarket());
            existing.setCategory(stockInfo.getCategory());
            stockInfoMapper.updateById(existing);
        }
    }
}

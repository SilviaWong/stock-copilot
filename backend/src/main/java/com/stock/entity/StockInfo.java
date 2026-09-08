package com.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * 标的字典与关注列表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("stock_info")
public class StockInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 标的代码，如 510300, 600519
     */
    private String symbol;

    /**
     * 标的名称，如 沪深300ETF
     */
    private String name;

    /**
     * 所属市场: SH(上海), SZ(深圳), BJ(北京)
     */
    private String market;

    /**
     * 分类: STOCK(股票), ETF(场内ETF)
     */
    private String category;

    /**
     * 是否自选关注: 1是, 0否
     */
    private Integer isFavorite;

    /**
     * 创建时间
     */
    private String createdAt;
}

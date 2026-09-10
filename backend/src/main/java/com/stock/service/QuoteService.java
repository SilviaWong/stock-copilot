package com.stock.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.dto.*;
import com.stock.entity.Position;
import com.stock.entity.TransactionRecord;
import com.stock.mapper.PositionMapper;
import com.stock.mapper.TransactionRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 免费公开股票/ETF实时行情服务 (基于腾讯财经接口)
 */
@Service
public class QuoteService {

    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);
    private static final String TENCENT_QUOTE_URL = "https://qt.gtimg.cn/q=";
    private static final Charset GBK_CHARSET = Charset.forName("GBK");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private PositionMapper positionMapper;

    @Autowired(required = false)
    private TransactionRecordMapper transactionRecordMapper;

    public QuoteService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
    }

    /**
     * 单个标的行情查询
     */
    public QuoteDTO fetchQuote(String symbol) {
        if (!StringUtils.hasText(symbol)) {
            return null;
        }
        Map<String, QuoteDTO> map = batchFetchQuotes(Collections.singletonList(symbol.trim()));
        return map.get(symbol.trim());
    }

    /**
     * 批量查询多个标的行情 (一次 HTTP 请求获取，极速高效)
     */
    public Map<String, QuoteDTO> batchFetchQuotes(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Collections.emptyMap();
        }

        // 过滤去重并转换为带市场前缀的代码 (如 sh510300, sz159915)
        List<String> validSymbols = symbols.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());

        if (validSymbols.isEmpty()) {
            return Collections.emptyMap();
        }

        String queryParams = validSymbols.stream()
                .map(this::toTencentSymbol)
                .collect(Collectors.joining(","));

        String url = TENCENT_QUOTE_URL + queryParams;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                String body = new String(response.body(), GBK_CHARSET);
                return parseTencentQuotes(body);
            } else {
                log.warn("拉取行情失败，HTTP状态码: {}", response.statusCode());
            }
        } catch (Exception e) {
            log.warn("拉取实时行情异常: {}，将降级处理", e.getMessage());
        }

        return Collections.emptyMap();
    }

    /**
     * 解析腾讯行情返回的字符串
     * 返回示例: v_sh510300="1~沪深300ETF~510300~3.985~3.972~3.980~...";
     */
    private Map<String, QuoteDTO> parseTencentQuotes(String responseBody) {
        Map<String, QuoteDTO> result = new HashMap<>();
        if (!StringUtils.hasText(responseBody)) {
            return result;
        }

        String[] lines = responseBody.split(";");
        for (String line : lines) {
            line = line.trim();
            if (!line.contains("=\"") || !line.endsWith("\"")) {
                continue;
            }

            int eqIdx = line.indexOf("=\"");
            String varName = line.substring(0, eqIdx).trim(); // 如 v_sh510300
            String dataStr = line.substring(eqIdx + 2, line.length() - 1); // 提取双引号内的内容

            String[] fields = dataStr.split("~");
            if (fields.length < 33) {
                continue;
            }

            try {
                String name = fields[1];
                String symbol = fields[2];
                BigDecimal currentPrice = new BigDecimal(fields[3]);
                BigDecimal yesterdayClose = new BigDecimal(fields[4]);
                BigDecimal openPrice = new BigDecimal(fields[5]);
                BigDecimal highPrice = fields.length > 33 && StringUtils.hasText(fields[33]) ? new BigDecimal(fields[33]) : currentPrice;
                BigDecimal lowPrice = fields.length > 34 && StringUtils.hasText(fields[34]) ? new BigDecimal(fields[34]) : currentPrice;
                BigDecimal changeAmount = fields.length > 31 && StringUtils.hasText(fields[31]) ? new BigDecimal(fields[31]) : BigDecimal.ZERO;
                BigDecimal changePercent = fields.length > 32 && StringUtils.hasText(fields[32]) ? new BigDecimal(fields[32]) : BigDecimal.ZERO;

                String timeStr = fields.length > 30 ? fields[30] : "";
                String formattedTime = formatTimestamp(timeStr);

                QuoteDTO quote = QuoteDTO.builder()
                        .symbol(symbol)
                        .name(name)
                        .market(resolveMarket(symbol))
                        .currentPrice(currentPrice)
                        .yesterdayClose(yesterdayClose)
                        .openPrice(openPrice)
                        .highPrice(highPrice)
                        .lowPrice(lowPrice)
                        .changeAmount(changeAmount)
                        .changePercent(changePercent)
                        .updateTime(formattedTime)
                        .build();

                result.put(symbol, quote);
            } catch (Exception ex) {
                log.warn("解析单条行情记录失败: {}, 错误: {}", line, ex.getMessage());
            }
        }
        return result;
    }

    /**
     * 将 6 位股票/ETF代码转为腾讯前缀 (sh / sz / bj)
     */
    public String toTencentSymbol(String symbol) {
        if (symbol.startsWith("sh") || symbol.startsWith("sz") || symbol.startsWith("bj")) {
            return symbol.toLowerCase();
        }
        String market = resolveMarket(symbol).toLowerCase();
        return market + symbol;
    }

    public String resolveMarket(String symbol) {
        if (symbol.startsWith("6") || symbol.startsWith("5")) {
            return "SH";
        } else if (symbol.startsWith("0") || symbol.startsWith("3") || symbol.startsWith("1")) {
            return "SZ";
        } else if (symbol.startsWith("8") || symbol.startsWith("4") || symbol.startsWith("9")) {
            return "BJ";
        }
        return "SH";
    }

    private String formatTimestamp(String raw) {
        if (raw != null && raw.length() == 14) {
            // yyyyMMddHHmmss -> yyyy-MM-dd HH:mm:ss
            return raw.substring(0, 4) + "-" + raw.substring(4, 6) + "-" + raw.substring(6, 8) + " "
                    + raw.substring(8, 10) + ":" + raw.substring(10, 12) + ":" + raw.substring(12, 14);
        }
        return raw;
    }

    /**
     * 获取 1 日分时图走势数据 (白线现价、黄线均价、成交量、持仓成本线、今日买卖打点)
     */
    public MinuteChartDTO fetchMinuteChart(String symbol) {
        if (!StringUtils.hasText(symbol)) {
            return null;
        }
        symbol = symbol.trim();
        String tCode = toTencentSymbol(symbol);
        String url = "https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=" + tCode;

        SymbolPositionInfo posInfo = queryPositionInfo(symbol);

        MinuteChartDTO.MinuteChartDTOBuilder builder = MinuteChartDTO.builder()
                .symbol(symbol)
                .name(posInfo.name)
                .holdQuantity(posInfo.holdQuantity)
                .costPrice(posInfo.costPrice)
                .dilutedCostPrice(posInfo.dilutedCostPrice)
                .times(new ArrayList<>())
                .prices(new ArrayList<>())
                .avgPrices(new ArrayList<>())
                .volumes(new ArrayList<>())
                .tradeMarkers(new ArrayList<>());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && StringUtils.hasText(response.body())) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode stockNode = root.path("data").path(tCode);
                if (!stockNode.isMissingNode()) {
                    JsonNode dataNode = stockNode.path("data");
                    String rawDate = dataNode.path("date").asText(); // "20260910"
                    String formattedDate = (rawDate != null && rawDate.length() == 8)
                            ? rawDate.substring(0, 4) + "-" + rawDate.substring(4, 6) + "-" + rawDate.substring(6, 8)
                            : rawDate;
                    builder.date(formattedDate);

                    JsonNode qtNode = stockNode.path("qt").path(tCode);
                    if (qtNode.isArray() && qtNode.size() > 34) {
                        if (!StringUtils.hasText(posInfo.name)) {
                            builder.name(qtNode.path(1).asText());
                        }
                        builder.latestPrice(toBigDecimal(qtNode.path(3).asText()));
                        builder.preClose(toBigDecimal(qtNode.path(4).asText()));
                        builder.changeAmount(toBigDecimal(qtNode.path(31).asText()));
                        builder.changePercent(toBigDecimal(qtNode.path(32).asText()));
                        builder.high(toBigDecimal(qtNode.path(33).asText()));
                        builder.low(toBigDecimal(qtNode.path(34).asText()));
                        if (qtNode.size() > 36) {
                            builder.totalVolume(toLong(qtNode.path(36).asText()));
                        }
                        if (qtNode.size() > 37) {
                            BigDecimal totalAmtWan = toBigDecimal(qtNode.path(37).asText());
                            if (totalAmtWan != null) {
                                builder.totalAmount(totalAmtWan.multiply(BigDecimal.valueOf(10000)));
                            }
                        }
                    }

                    JsonNode minuteArr = dataNode.path("data");
                    if (minuteArr.isArray()) {
                        List<String> times = new ArrayList<>();
                        List<BigDecimal> prices = new ArrayList<>();
                        List<BigDecimal> avgPrices = new ArrayList<>();
                        List<Long> volumes = new ArrayList<>();

                        long prevCumVol = 0;
                        for (JsonNode mItem : minuteArr) {
                            String mStr = mItem.asText();
                            String[] parts = mStr.split(" ");
                            if (parts.length >= 4) {
                                String t = parts[0];
                                String formattedTime = (t.length() == 4) ? t.substring(0, 2) + ":" + t.substring(2, 4) : t;
                                BigDecimal price = new BigDecimal(parts[1]);
                                long cumVol = Long.parseLong(parts[2]);
                                BigDecimal cumAmt = new BigDecimal(parts[3]);

                                BigDecimal avgPrice = (cumVol > 0)
                                        ? cumAmt.divide(BigDecimal.valueOf(cumVol * 100L), 3, RoundingMode.HALF_UP)
                                        : price;

                                long vol = cumVol - prevCumVol;
                                if (vol < 0) vol = 0;
                                prevCumVol = cumVol;

                                times.add(formattedTime);
                                prices.add(price);
                                avgPrices.add(avgPrice);
                                volumes.add(vol);
                            }
                        }
                        builder.times(times);
                        builder.prices(prices);
                        builder.avgPrices(avgPrices);
                        builder.volumes(volumes);
                    }

                    // 当日交易点图钉 (匹配今日日期)
                    if (formattedDate != null && !posInfo.allMarkers.isEmpty()) {
                        List<TradeMarkerDTO> todayMarkers = posInfo.allMarkers.stream()
                                .filter(m -> formattedDate.equals(m.getTradeDate()))
                                .collect(Collectors.toList());
                        builder.tradeMarkers(todayMarkers);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("获取分时图数据异常: {}", e.getMessage());
        }

        return builder.build();
    }

    /**
     * 获取连续 5 日分时走势数据 (扁平连续序列、日期间隔线、持仓成本线)
     */
    public FiveDayChartDTO fetchFiveDayChart(String symbol) {
        if (!StringUtils.hasText(symbol)) {
            return null;
        }
        symbol = symbol.trim();
        String tCode = toTencentSymbol(symbol);
        String url = "https://web.ifzq.gtimg.cn/appstock/app/day/query?code=" + tCode;

        SymbolPositionInfo posInfo = queryPositionInfo(symbol);

        FiveDayChartDTO.FiveDayChartDTOBuilder builder = FiveDayChartDTO.builder()
                .symbol(symbol)
                .name(posInfo.name)
                .holdQuantity(posInfo.holdQuantity)
                .costPrice(posInfo.costPrice)
                .dilutedCostPrice(posInfo.dilutedCostPrice)
                .times(new ArrayList<>())
                .prices(new ArrayList<>())
                .avgPrices(new ArrayList<>())
                .volumes(new ArrayList<>())
                .splitIndexes(new ArrayList<>())
                .dayLabels(new ArrayList<>());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && StringUtils.hasText(response.body())) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode stockNode = root.path("data").path(tCode);
                JsonNode daysNode = stockNode.path("data");

                if (daysNode.isArray() && daysNode.size() > 0) {
                    // 腾讯返回的是倒序 (最新的在前面)，需要翻转为正序 (从第1天到第5天)
                    List<JsonNode> dayList = new ArrayList<>();
                    for (JsonNode dayNode : daysNode) {
                        dayList.add(dayNode);
                    }
                    Collections.reverse(dayList);

                    List<String> times = new ArrayList<>();
                    List<BigDecimal> prices = new ArrayList<>();
                    List<BigDecimal> avgPrices = new ArrayList<>();
                    List<Long> volumes = new ArrayList<>();
                    List<Integer> splitIndexes = new ArrayList<>();
                    List<String> dayLabels = new ArrayList<>();
                    BigDecimal basePrice = null;

                    for (JsonNode dayNode : dayList) {
                        String rawDate = dayNode.path("date").asText(); // "20260904"
                        String dateLabel = (rawDate.length() == 8)
                                ? rawDate.substring(4, 6) + "-" + rawDate.substring(6, 8)
                                : rawDate;
                        dayLabels.add(dateLabel);
                        splitIndexes.add(times.size());

                        if (basePrice == null && dayNode.has("prec")) {
                            basePrice = toBigDecimal(dayNode.path("prec").asText());
                        }

                        JsonNode minuteArr = dayNode.path("data");
                        if (minuteArr.isArray()) {
                            long prevCumVol = 0;
                            for (JsonNode mItem : minuteArr) {
                                String[] parts = mItem.asText().split(" ");
                                if (parts.length >= 4) {
                                    String t = parts[0];
                                    String formattedTime = dateLabel + " " + ((t.length() == 4) ? t.substring(0, 2) + ":" + t.substring(2, 4) : t);
                                    BigDecimal price = new BigDecimal(parts[1]);
                                    long cumVol = Long.parseLong(parts[2]);
                                    BigDecimal cumAmt = new BigDecimal(parts[3]);

                                    BigDecimal avgPrice = (cumVol > 0)
                                            ? cumAmt.divide(BigDecimal.valueOf(cumVol * 100L), 3, RoundingMode.HALF_UP)
                                            : price;

                                    long vol = cumVol - prevCumVol;
                                    if (vol < 0) vol = 0;
                                    prevCumVol = cumVol;

                                    times.add(formattedTime);
                                    prices.add(price);
                                    avgPrices.add(avgPrice);
                                    volumes.add(vol);
                                }
                            }
                        }
                    }

                    builder.basePrice(basePrice != null ? basePrice : (prices.isEmpty() ? BigDecimal.ZERO : prices.get(0)));
                    builder.times(times);
                    builder.prices(prices);
                    builder.avgPrices(avgPrices);
                    builder.volumes(volumes);
                    builder.splitIndexes(splitIndexes);
                    builder.dayLabels(dayLabels);
                }
            }
        } catch (Exception e) {
            log.warn("获取五日分时数据异常: {}", e.getMessage());
        }

        return builder.build();
    }

    /**
     * 获取日 K 线数据 (包含蜡烛图、均线系统、成交量、持仓成本生命线与历史买卖打点)
     */
    public KlineChartDTO fetchKlineChart(String symbol) {
        if (!StringUtils.hasText(symbol)) {
            return null;
        }
        symbol = symbol.trim();
        String tCode = toTencentSymbol(symbol);
        String url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=" + tCode + ",day,,,320,qfq";

        SymbolPositionInfo posInfo = queryPositionInfo(symbol);

        KlineChartDTO.KlineChartDTOBuilder builder = KlineChartDTO.builder()
                .symbol(symbol)
                .name(posInfo.name)
                .holdQuantity(posInfo.holdQuantity)
                .costPrice(posInfo.costPrice)
                .dilutedCostPrice(posInfo.dilutedCostPrice)
                .dates(new ArrayList<>())
                .values(new ArrayList<>())
                .volumes(new ArrayList<>())
                .ma5(new ArrayList<>())
                .ma10(new ArrayList<>())
                .ma20(new ArrayList<>())
                .ma60(new ArrayList<>())
                .tradeMarkers(posInfo.allMarkers);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && StringUtils.hasText(response.body())) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode stockNode = root.path("data").path(tCode);

                JsonNode klineArr = stockNode.has("qfqday") && stockNode.path("qfqday").size() > 0
                        ? stockNode.path("qfqday")
                        : stockNode.path("day");

                if (stockNode.has("qt") && stockNode.path("qt").has(tCode)) {
                    JsonNode qt = stockNode.path("qt").path(tCode);
                    if (!StringUtils.hasText(posInfo.name) && qt.size() > 1) {
                        builder.name(qt.get(1).asText());
                    }
                }

                if (klineArr.isArray() && klineArr.size() > 0) {
                    List<String> dates = new ArrayList<>();
                    List<List<BigDecimal>> values = new ArrayList<>();
                    List<Long> volumes = new ArrayList<>();
                    List<BigDecimal> closes = new ArrayList<>();

                    for (JsonNode item : klineArr) {
                        if (item.isArray() && item.size() >= 6) {
                            String date = item.get(0).asText();
                            BigDecimal open = toBigDecimal(item.get(1).asText());
                            BigDecimal close = toBigDecimal(item.get(2).asText());
                            BigDecimal high = toBigDecimal(item.get(3).asText());
                            BigDecimal low = toBigDecimal(item.get(4).asText());
                            long vol = Math.round(Double.parseDouble(item.get(5).asText()));

                            dates.add(date);
                            // ECharts Candlestick 规范: [open, close, lowest, highest]
                            values.add(Arrays.asList(open, close, low, high));
                            volumes.add(vol);
                            closes.add(close);
                        }
                    }

                    builder.dates(dates);
                    builder.values(values);
                    builder.volumes(volumes);
                    builder.ma5(calculateMA(5, closes));
                    builder.ma10(calculateMA(10, closes));
                    builder.ma20(calculateMA(20, closes));
                    builder.ma60(calculateMA(60, closes));
                }
            }
        } catch (Exception e) {
            log.warn("获取日K线数据异常: {}", e.getMessage());
        }

        return builder.build();
    }

    private List<BigDecimal> calculateMA(int dayCount, List<BigDecimal> closes) {
        List<BigDecimal> result = new ArrayList<>(closes.size());
        for (int i = 0; i < closes.size(); i++) {
            if (i < dayCount - 1) {
                result.add(null);
            } else {
                BigDecimal sum = BigDecimal.ZERO;
                for (int j = 0; j < dayCount; j++) {
                    sum = sum.add(closes.get(i - j));
                }
                result.add(sum.divide(BigDecimal.valueOf(dayCount), 3, RoundingMode.HALF_UP));
            }
        }
        return result;
    }

    private BigDecimal toBigDecimal(String val) {
        if (!StringUtils.hasText(val)) return null;
        try {
            return new BigDecimal(val.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Long toLong(String val) {
        if (!StringUtils.hasText(val)) return null;
        try {
            return Long.parseLong(val.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 辅助类：封装当前标的持仓与历史流水
     */
    private static class SymbolPositionInfo {
        int holdQuantity = 0;
        BigDecimal costPrice = null;
        BigDecimal dilutedCostPrice = null;
        String name = "";
        List<TradeMarkerDTO> allMarkers = Collections.emptyList();
    }

    private SymbolPositionInfo queryPositionInfo(String symbol) {
        SymbolPositionInfo info = new SymbolPositionInfo();
        if (positionMapper == null || transactionRecordMapper == null) {
            return info;
        }
        try {
            Position position = positionMapper.selectOne(
                    new LambdaQueryWrapper<Position>().eq(Position::getSymbol, symbol)
            );
            List<TransactionRecord> records = transactionRecordMapper.selectList(
                    new LambdaQueryWrapper<TransactionRecord>()
                            .eq(TransactionRecord::getSymbol, symbol)
                            .orderByAsc(TransactionRecord::getTradeTime)
            );

            if (position != null) {
                info.name = position.getName();
                info.holdQuantity = position.getHoldQuantity() != null ? position.getHoldQuantity() : 0;
                info.costPrice = position.getCostPrice();
            }

            BigDecimal totalBuyCash = BigDecimal.ZERO;
            BigDecimal totalSellCash = BigDecimal.ZERO;
            BigDecimal totalFees = BigDecimal.ZERO;

            List<TradeMarkerDTO> markers = new ArrayList<>();
            for (TransactionRecord r : records) {
                if (!StringUtils.hasText(info.name) && StringUtils.hasText(r.getName())) {
                    info.name = r.getName();
                }
                BigDecimal fee = r.getFee() != null ? r.getFee() : BigDecimal.ZERO;
                totalFees = totalFees.add(fee);
                if ("BUY".equalsIgnoreCase(r.getAction())) {
                    totalBuyCash = totalBuyCash.add(r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO);
                } else if ("SELL".equalsIgnoreCase(r.getAction()) || "DIVIDEND".equalsIgnoreCase(r.getAction())) {
                    totalSellCash = totalSellCash.add(r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO);
                }

                String tradeTime = r.getTradeTime() != null ? r.getTradeTime() : "";
                String tradeDate = tradeTime.length() >= 10 ? tradeTime.substring(0, 10) : "";
                String label = "BUY".equalsIgnoreCase(r.getAction()) ? "B" : ("SELL".equalsIgnoreCase(r.getAction()) ? "S" : "D");

                markers.add(TradeMarkerDTO.builder()
                        .id(r.getId())
                        .tradeDate(tradeDate)
                        .tradeTime(tradeTime)
                        .action(r.getAction())
                        .price(r.getPrice())
                        .quantity(r.getQuantity())
                        .amount(r.getAmount())
                        .fee(r.getFee())
                        .realizedPnl(r.getRealizedPnl())
                        .strategyTag(r.getStrategyTag())
                        .label(label)
                        .build());
            }

            info.allMarkers = markers;

            if (records.isEmpty()) {
                info.dilutedCostPrice = info.costPrice;
            } else if (info.holdQuantity > 0) {
                BigDecimal dilutedTotalCost = totalBuyCash.subtract(totalSellCash).add(totalFees);
                info.dilutedCostPrice = dilutedTotalCost.divide(BigDecimal.valueOf(info.holdQuantity), 4, RoundingMode.HALF_UP);
            } else {
                info.dilutedCostPrice = BigDecimal.ZERO;
            }
        } catch (Exception e) {
            log.warn("查询标的持仓辅助信息失败: {}", e.getMessage());
        }
        return info;
    }
}


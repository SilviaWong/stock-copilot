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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 大盘全景行情短时缓存 (5秒)
    private volatile MarketOverviewDTO cachedMarketOverview;
    private volatile long marketOverviewCacheTime = 0;
    private static final long MARKET_CACHE_DURATION_MS = 5000;

    @Autowired(required = false)
    private PositionMapper positionMapper;

    @Autowired(required = false)
    private TransactionRecordMapper transactionRecordMapper;

    @Autowired
    private QuantIndicatorService quantIndicatorService;

    public QuoteService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
    }

    /**
     * 标的基准指数信息映射类
     */
    public static class BenchmarkInfo {
        private final String symbol;
        private final String name;

        public BenchmarkInfo(String symbol, String name) {
            this.symbol = symbol;
            this.name = name;
        }

        public String getSymbol() {
            return symbol;
        }

        public String getName() {
            return name;
        }
    }

    /**
     * 根据股票或 ETF 代码自动判定其宏观基准指数 (创业板指 / 科创50 / 上证指数 / 深证成指)
     */
    public BenchmarkInfo resolveBenchmark(String symbol) {
        if (!StringUtils.hasText(symbol)) {
            return new BenchmarkInfo("sh000001", "上证指数");
        }
        String clean = symbol.trim().toLowerCase().replaceAll("^(sh|sz|bj)", "");
        if (clean.startsWith("300") || clean.startsWith("301") || clean.startsWith("159")) {
            return new BenchmarkInfo("sz399006", "创业板指");
        } else if (clean.startsWith("688") || clean.startsWith("588")) {
            return new BenchmarkInfo("sh000688", "科创50");
        } else if (clean.startsWith("00") || clean.startsWith("399")) {
            return new BenchmarkInfo("sz399001", "深证成指");
        } else {
            return new BenchmarkInfo("sh000001", "上证指数");
        }
    }

    /**
     * 获取全市场宏观大盘全景 (四大核心指数、两市总成交额、多空情绪温度计)
     */
    public MarketOverviewDTO getMarketOverview() {
        long now = System.currentTimeMillis();
        if (cachedMarketOverview != null && (now - marketOverviewCacheTime) < MARKET_CACHE_DURATION_MS) {
            return cachedMarketOverview;
        }

        String url = TENCENT_QUOTE_URL + "sh000001,sz399001,sz399006,sh000688";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(4))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                String body = new String(response.body(), GBK_CHARSET);
                MarketOverviewDTO overview = parseMarketOverview(body);
                if (overview != null && overview.getIndices() != null && !overview.getIndices().isEmpty()) {
                    cachedMarketOverview = overview;
                    marketOverviewCacheTime = now;
                    return overview;
                }
            }
        } catch (Exception e) {
            log.warn("拉取大盘指数全貌异常: {}，将降级处理", e.getMessage());
        }

        MarketOverviewDTO fallback = buildFallbackMarketOverview();
        cachedMarketOverview = fallback;
        marketOverviewCacheTime = now;
        return fallback;
    }

    /**
     * 解析腾讯行情返回的大盘指数数据并计算两市成交额与情绪温度
     */
    private MarketOverviewDTO parseMarketOverview(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return null;
        }

        Map<String, IndexQuoteDTO> indexMap = new HashMap<>();
        String[] lines = responseBody.split(";");
        for (String line : lines) {
            line = line.trim();
            if (!line.contains("=\"") || !line.endsWith("\"")) {
                continue;
            }

            int eqIdx = line.indexOf("=\"");
            String varName = line.substring(0, eqIdx).trim(); // v_sh000001
            String fullSymbol = varName.replace("v_", "");
            String dataStr = line.substring(eqIdx + 2, line.length() - 1);
            String[] fields = dataStr.split("~");
            if (fields.length < 33) {
                continue;
            }

            try {
                String name = fields[1];
                BigDecimal currentPoints = new BigDecimal(fields[3]);
                BigDecimal yesterdayClose = new BigDecimal(fields[4]);
                BigDecimal changeAmount = fields.length > 31 && StringUtils.hasText(fields[31]) ? new BigDecimal(fields[31]) : BigDecimal.ZERO;
                BigDecimal changePercent = fields.length > 32 && StringUtils.hasText(fields[32]) ? new BigDecimal(fields[32]) : BigDecimal.ZERO;

                // fields[37] 是成交额 (万元)
                BigDecimal turnoverWan = fields.length > 37 && StringUtils.hasText(fields[37]) ? new BigDecimal(fields[37]) : BigDecimal.ZERO;
                BigDecimal turnoverYi = turnoverWan.divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP);

                // fields[6] 是成交量 (手)
                BigDecimal volumeWanShou = fields.length > 6 && StringUtils.hasText(fields[6])
                        ? new BigDecimal(fields[6]).divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;

                String timeStr = fields.length > 30 ? fields[30] : "";
                String formattedTime = formatTimestamp(timeStr);

                IndexQuoteDTO indexDTO = IndexQuoteDTO.builder()
                        .symbol(fullSymbol)
                        .name(name)
                        .currentPoints(currentPoints)
                        .yesterdayClose(yesterdayClose)
                        .changeAmount(changeAmount)
                        .changePercent(changePercent)
                        .turnoverAmount(turnoverYi)
                        .volume(volumeWanShou)
                        .updateTime(formattedTime)
                        .build();

                indexMap.put(fullSymbol, indexDTO);
            } catch (Exception ex) {
                log.warn("解析大盘单条记录失败: {}, 错误: {}", line, ex.getMessage());
            }
        }

        if (indexMap.isEmpty()) {
            return null;
        }

        List<IndexQuoteDTO> indices = new ArrayList<>();
        String[] order = {"sh000001", "sz399001", "sz399006", "sh000688"};
        for (String code : order) {
            if (indexMap.containsKey(code)) {
                indices.add(indexMap.get(code));
            }
        }

        BigDecimal shTurnover = indexMap.containsKey("sh000001") ? indexMap.get("sh000001").getTurnoverAmount() : BigDecimal.ZERO;
        BigDecimal szTurnover = indexMap.containsKey("sz399001") ? indexMap.get("sz399001").getTurnoverAmount() : BigDecimal.ZERO;
        BigDecimal totalTurnover = (shTurnover != null ? shTurnover : BigDecimal.ZERO)
                .add(szTurnover != null ? szTurnover : BigDecimal.ZERO);

        double sumChg = 0;
        int count = 0;
        for (IndexQuoteDTO idx : indices) {
            if (idx.getChangePercent() != null) {
                sumChg += idx.getChangePercent().doubleValue();
                count++;
            }
        }
        double avgChg = count > 0 ? sumChg / count : 0.0;

        int volBonus = 0;
        String turnoverStatus;
        double tt = totalTurnover.doubleValue();
        if (tt >= 15000) {
            volBonus = 12;
            turnoverStatus = String.format("放量活跃 (两市¥%.2f万亿)", tt / 10000.0);
        } else if (tt >= 10000) {
            volBonus = 6;
            turnoverStatus = String.format("温和放量 (两市¥%.2f万亿)", tt / 10000.0);
        } else if (tt >= 8000) {
            volBonus = 0;
            turnoverStatus = String.format("存量博弈 (两市¥%d亿)", (int) tt);
        } else if (tt > 0) {
            volBonus = -8;
            turnoverStatus = String.format("缩量地量 (两市¥%d亿)", (int) tt);
        } else {
            turnoverStatus = "交投平稳";
        }

        int score = (int) Math.round(50 + avgChg * 12 + volBonus);
        if (score > 95) score = 95;
        if (score < 10) score = 10;

        String level, title, desc;
        if (score >= 75) {
            level = "FEVER";
            title = "🔥 情绪过热 / 极度活跃";
            desc = "两市量能充沛，赚钱效应扩散；适宜逢高对大幅冲高标的分批减仓落袋做T，切忌盲目追高接盘。";
        } else if (score >= 58) {
            level = "BULLISH";
            title = "🟢 偏多温和 / 结构反弹";
            desc = "指数呈现震荡上行反弹走势，主线赛道承接有力；可持股为主，依托关键支撑网格进行低吸做T。";
        } else if (score >= 45) {
            level = "NEUTRAL";
            title = "⚪ 窄幅震荡 / 存量平衡";
            desc = "多空处于均衡博弈阶段，板块轮动较快；多看少动，坚决执行在网格支撑吸、阻力抛的做T纪律。";
        } else if (score >= 30) {
            level = "BEARISH";
            title = "🟡 偏弱分化 / 亏钱效应";
            desc = "大盘重心小幅下移，防御避险情绪升温；控制总体持仓风险，逆市加仓时需严格控制为轻仓分批。";
        } else {
            level = "PANIC";
            title = "❄️ 恐慌冰点 / 极端超跌";
            desc = "盘面出现恐慌性集中杀跌，但技术面进入极度超卖区；切莫在冰点最低点恐慌割肉，等待企稳信号。";
        }

        return MarketOverviewDTO.builder()
                .indices(indices)
                .totalTurnover(totalTurnover)
                .shTurnover(shTurnover)
                .szTurnover(szTurnover)
                .turnoverStatus(turnoverStatus)
                .sentimentScore(score)
                .sentimentLevel(level)
                .sentimentTitle(title)
                .sentimentDesc(desc)
                .updateTime(LocalDateTime.now().format(TIME_FORMATTER))
                .build();
    }

    /**
     * 离线或弱网环境下的降级大盘全貌
     */
    private MarketOverviewDTO buildFallbackMarketOverview() {
        List<IndexQuoteDTO> indices = Arrays.asList(
                IndexQuoteDTO.builder().symbol("sh000001").name("上证指数").currentPoints(new BigDecimal("3352.68")).yesterdayClose(new BigDecimal("3345.10")).changeAmount(new BigDecimal("7.58")).changePercent(new BigDecimal("0.23")).turnoverAmount(new BigDecimal("5240.50")).volume(new BigDecimal("38200.00")).updateTime(LocalDateTime.now().format(TIME_FORMATTER)).build(),
                IndexQuoteDTO.builder().symbol("sz399001").name("深证成指").currentPoints(new BigDecimal("10830.15")).yesterdayClose(new BigDecimal("10780.00")).changeAmount(new BigDecimal("50.15")).changePercent(new BigDecimal("0.47")).turnoverAmount(new BigDecimal("7310.20")).volume(new BigDecimal("49100.00")).updateTime(LocalDateTime.now().format(TIME_FORMATTER)).build(),
                IndexQuoteDTO.builder().symbol("sz399006").name("创业板指").currentPoints(new BigDecimal("2218.42")).yesterdayClose(new BigDecimal("2200.12")).changeAmount(new BigDecimal("18.30")).changePercent(new BigDecimal("0.83")).turnoverAmount(new BigDecimal("3450.80")).volume(new BigDecimal("19500.00")).updateTime(LocalDateTime.now().format(TIME_FORMATTER)).build(),
                IndexQuoteDTO.builder().symbol("sh000688").name("科创50").currentPoints(new BigDecimal("1025.30")).yesterdayClose(new BigDecimal("1020.00")).changeAmount(new BigDecimal("5.30")).changePercent(new BigDecimal("0.52")).turnoverAmount(new BigDecimal("1210.30")).volume(new BigDecimal("8200.00")).updateTime(LocalDateTime.now().format(TIME_FORMATTER)).build()
        );

        BigDecimal total = new BigDecimal("12550.70");
        return MarketOverviewDTO.builder()
                .indices(indices)
                .totalTurnover(total)
                .shTurnover(new BigDecimal("5240.50"))
                .szTurnover(new BigDecimal("7310.20"))
                .turnoverStatus("温和放量 (两市¥1.26万亿)")
                .sentimentScore(62)
                .sentimentLevel("BULLISH")
                .sentimentTitle("🟢 偏多温和 / 结构反弹")
                .sentimentDesc("指数呈现震荡上行反弹走势，主线赛道承接有力；可持股为主，依托关键支撑网格进行低吸做T。")
                .updateTime(LocalDateTime.now().format(TIME_FORMATTER))
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
                    List<BigDecimal> highs = new ArrayList<>();
                    List<BigDecimal> lows = new ArrayList<>();

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
                            highs.add(high);
                            lows.add(low);
                        }
                    }

                    builder.dates(dates);
                    builder.values(values);
                    builder.volumes(volumes);
                    builder.ma5(calculateMA(5, closes));
                    builder.ma10(calculateMA(10, closes));
                    builder.ma20(calculateMA(20, closes));
                    builder.ma60(calculateMA(60, closes));

                    if (quantIndicatorService != null && !closes.isEmpty()) {
                        // 1. 布林带系统 (BOLL: 20, 2)
                        QuantIndicatorService.BollResult boll = quantIndicatorService.calculateBoll(closes, 20, 2.0);
                        builder.bollMid(boll.getMid());
                        builder.bollUpper(boll.getUpper());
                        builder.bollLower(boll.getLower());

                        // 2. MACD 经典系统 (12, 26, 9)
                        QuantIndicatorService.MacdResult macd = quantIndicatorService.calculateMacd(closes, 12, 26, 9);
                        builder.macdDif(macd.getDif());
                        builder.macdDea(macd.getDea());
                        builder.macdBar(macd.getBar());

                        // 3. KDJ 随机指标 (9, 3, 3)
                        QuantIndicatorService.KdjResult kdj = quantIndicatorService.calculateKdj(highs, lows, closes, 9, 3, 3);
                        builder.kdjK(kdj.getK());
                        builder.kdjD(kdj.getD());
                        builder.kdjJ(kdj.getJ());

                        // 4. 次日 Pivot Points (基于最新一个完整交易日的 High, Low, Close)
                        int lastIdx = closes.size() - 1;
                        BigDecimal lastHigh = highs.get(lastIdx);
                        BigDecimal lastLow = lows.get(lastIdx);
                        BigDecimal lastClose = closes.get(lastIdx);
                        String lastDate = dates.get(lastIdx);
                        PivotPointsDTO pivot = quantIndicatorService.calculatePivotPoints(
                                symbol, lastDate, lastHigh, lastLow, lastClose, lastClose
                        );
                        builder.pivotPoints(pivot);
                    }
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


package com.stock.service;

import com.stock.dto.QuoteDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
}

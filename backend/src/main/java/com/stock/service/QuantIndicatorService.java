package com.stock.service;

import com.stock.dto.PivotPointsDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 专业量化技术指标计算引擎 (BOLL, MACD, KDJ, Pivot Points)
 */
@Service
public class QuantIndicatorService {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BollResult {
        private List<BigDecimal> mid;
        private List<BigDecimal> upper;
        private List<BigDecimal> lower;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MacdResult {
        private List<BigDecimal> dif;
        private List<BigDecimal> dea;
        private List<BigDecimal> bar;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KdjResult {
        private List<BigDecimal> k;
        private List<BigDecimal> d;
        private List<BigDecimal> j;
    }

    /**
     * 计算布林线 (BOLL: period=20, k=2.0)
     * MID = MA20
     * UPPER = MID + 2 * 标准差
     * LOWER = MID - 2 * 标准差
     */
    public BollResult calculateBoll(List<BigDecimal> closes, int period, double k) {
        if (closes == null || closes.isEmpty()) {
            return BollResult.builder()
                    .mid(new ArrayList<>())
                    .upper(new ArrayList<>())
                    .lower(new ArrayList<>())
                    .build();
        }

        int size = closes.size();
        List<BigDecimal> midList = new ArrayList<>(size);
        List<BigDecimal> upperList = new ArrayList<>(size);
        List<BigDecimal> lowerList = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            if (i < period - 1) {
                midList.add(null);
                upperList.add(null);
                lowerList.add(null);
            } else {
                double sum = 0.0;
                for (int j = 0; j < period; j++) {
                    sum += closes.get(i - j).doubleValue();
                }
                double mean = sum / period;

                double varianceSum = 0.0;
                for (int j = 0; j < period; j++) {
                    double diff = closes.get(i - j).doubleValue() - mean;
                    varianceSum += diff * diff;
                }
                double stdDev = Math.sqrt(varianceSum / period);

                BigDecimal mid = BigDecimal.valueOf(mean).setScale(3, RoundingMode.HALF_UP);
                BigDecimal upper = BigDecimal.valueOf(mean + k * stdDev).setScale(3, RoundingMode.HALF_UP);
                BigDecimal lower = BigDecimal.valueOf(mean - k * stdDev).setScale(3, RoundingMode.HALF_UP);

                midList.add(mid);
                upperList.add(upper);
                lowerList.add(lower);
            }
        }

        return BollResult.builder()
                .mid(midList)
                .upper(upperList)
                .lower(lowerList)
                .build();
    }

    /**
     * 计算平滑异同移动平均线 (MACD: shortPeriod=12, longPeriod=26, signalPeriod=9)
     * DIF = EMA(12) - EMA(26)
     * DEA = EMA(9, DIF)
     * MACD柱 = 2 * (DIF - DEA)
     */
    public MacdResult calculateMacd(List<BigDecimal> closes, int shortPeriod, int longPeriod, int signalPeriod) {
        if (closes == null || closes.isEmpty()) {
            return MacdResult.builder()
                    .dif(new ArrayList<>())
                    .dea(new ArrayList<>())
                    .bar(new ArrayList<>())
                    .build();
        }

        int size = closes.size();
        List<BigDecimal> difList = new ArrayList<>(size);
        List<BigDecimal> deaList = new ArrayList<>(size);
        List<BigDecimal> barList = new ArrayList<>(size);

        double emaShort = closes.get(0).doubleValue();
        double emaLong = closes.get(0).doubleValue();
        double dea = 0.0;

        double kShort = 2.0 / (shortPeriod + 1.0);
        double kLong = 2.0 / (longPeriod + 1.0);
        double kSignal = 2.0 / (signalPeriod + 1.0);

        for (int i = 0; i < size; i++) {
            double price = closes.get(i).doubleValue();
            if (i > 0) {
                emaShort = price * kShort + emaShort * (1.0 - kShort);
                emaLong = price * kLong + emaLong * (1.0 - kLong);
            }
            double dif = emaShort - emaLong;

            if (i == 0) {
                dea = dif;
            } else {
                dea = dif * kSignal + dea * (1.0 - kSignal);
            }

            BigDecimal difBd = BigDecimal.valueOf(dif).setScale(3, RoundingMode.HALF_UP);
            BigDecimal deaBd = BigDecimal.valueOf(dea).setScale(3, RoundingMode.HALF_UP);
            BigDecimal barBd = difBd.subtract(deaBd).multiply(BigDecimal.valueOf(2)).setScale(3, RoundingMode.HALF_UP);

            difList.add(difBd);
            deaList.add(deaBd);
            barList.add(barBd);
        }

        return MacdResult.builder()
                .dif(difList)
                .dea(deaList)
                .bar(barList)
                .build();
    }

    /**
     * 计算随机指标 (KDJ: n=9, m1=3, m2=3)
     * RSV = (Close - Low_n) / (High_n - Low_n) * 100
     * K = 2/3 * K_prev + 1/3 * RSV
     * D = 2/3 * D_prev + 1/3 * K
     * J = 3 * K - 2 * D
     */
    public KdjResult calculateKdj(List<BigDecimal> highs, List<BigDecimal> lows, List<BigDecimal> closes,
                                  int n, int m1, int m2) {
        if (closes == null || closes.isEmpty() || highs == null || lows == null) {
            return KdjResult.builder()
                    .k(new ArrayList<>())
                    .d(new ArrayList<>())
                    .j(new ArrayList<>())
                    .build();
        }

        int size = closes.size();
        List<BigDecimal> kList = new ArrayList<>(size);
        List<BigDecimal> dList = new ArrayList<>(size);
        List<BigDecimal> jList = new ArrayList<>(size);

        double kVal = 50.0;
        double dVal = 50.0;

        for (int i = 0; i < size; i++) {
            int start = Math.max(0, i - n + 1);
            double highN = -Double.MAX_VALUE;
            double lowN = Double.MAX_VALUE;

            for (int idx = start; idx <= i; idx++) {
                double h = highs.get(idx).doubleValue();
                double l = lows.get(idx).doubleValue();
                if (h > highN) highN = h;
                if (l < lowN) lowN = l;
            }

            double close = closes.get(i).doubleValue();
            double rsv = 50.0;
            if (highN > lowN) {
                rsv = ((close - lowN) / (highN - lowN)) * 100.0;
            }

            kVal = (2.0 / m1) * kVal + (1.0 / m1) * rsv;
            dVal = (2.0 / m2) * dVal + (1.0 / m2) * kVal;

            BigDecimal kBd = BigDecimal.valueOf(kVal).setScale(2, RoundingMode.HALF_UP);
            BigDecimal dBd = BigDecimal.valueOf(dVal).setScale(2, RoundingMode.HALF_UP);
            BigDecimal jBd = kBd.multiply(BigDecimal.valueOf(3)).subtract(dBd.multiply(BigDecimal.valueOf(2))).setScale(2, RoundingMode.HALF_UP);

            kList.add(kBd);
            dList.add(dBd);
            jList.add(jBd);
        }

        return KdjResult.builder()
                .k(kList)
                .d(dList)
                .j(jList)
                .build();
    }

    /**
     * 计算次日 Pivot Points (经典场内交易员轴心点系统)
     * P = (High + Low + Close) / 3
     * R1 = 2P - Low
     * R2 = P + (High - Low)
     * S1 = 2P - High
     * S2 = P - (High - Low)
     */
    public PivotPointsDTO calculatePivotPoints(String symbol, String baseDate,
                                               BigDecimal high, BigDecimal low, BigDecimal close,
                                               BigDecimal currentPrice) {
        if (high == null || low == null || close == null) {
            return null;
        }

        double h = high.doubleValue();
        double l = low.doubleValue();
        double c = close.doubleValue();
        double curr = (currentPrice != null) ? currentPrice.doubleValue() : c;

        double p = (h + l + c) / 3.0;
        double r1 = 2.0 * p - l;
        double r2 = p + (h - l);
        double s1 = 2.0 * p - h;
        double s2 = p - (h - l);

        String status;
        if (curr >= r1) {
            status = "多头强势上攻";
        } else if (curr >= p) {
            status = "偏多震荡运行";
        } else if (curr <= s1) {
            status = "弱势破位寻底";
        } else {
            status = "偏空弱势整理";
        }

        BigDecimal pBd = BigDecimal.valueOf(p).setScale(3, RoundingMode.HALF_UP);
        BigDecimal r1Bd = BigDecimal.valueOf(r1).setScale(3, RoundingMode.HALF_UP);
        BigDecimal r2Bd = BigDecimal.valueOf(r2).setScale(3, RoundingMode.HALF_UP);
        BigDecimal s1Bd = BigDecimal.valueOf(s1).setScale(3, RoundingMode.HALF_UP);
        BigDecimal s2Bd = BigDecimal.valueOf(s2).setScale(3, RoundingMode.HALF_UP);

        String actionTip = String.format(
                "次日多空轴心 P: ¥%s。若回踩 S1 (¥%s) 企稳可轻仓低吸；冲高至 R1 (¥%s) 遇阻可逢高做T减仓；极限进攻看 R2 (¥%s)，跌破 S2 (¥%s) 需防守。",
                pBd, s1Bd, r1Bd, r2Bd, s2Bd
        );

        return PivotPointsDTO.builder()
                .symbol(symbol)
                .baseDate(baseDate)
                .baseClose(close.setScale(3, RoundingMode.HALF_UP))
                .pivot(pBd)
                .r1(r1Bd)
                .r2(r2Bd)
                .s1(s1Bd)
                .s2(s2Bd)
                .status(status)
                .actionTip(actionTip)
                .build();
    }
}

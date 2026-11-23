package com.adperf.aggregator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;

/**
 * Totals and derived ratios for one campaign. Spend is kept in exact integer cents.
 *
 * <p>Rankings compare {@code double} ratios. This is safe for ties because IEEE division is correctly
 * rounded: two ratios with the same exact value always give the same double. Output values are computed
 * exactly with {@link BigDecimal} and rounded half-up.
 */
record Campaign(String id, long impressions, long clicks, long spendCents, long conversions) {

    /** Highest CTR first; ties by campaign id so the output is deterministic. */
    static final Comparator<Campaign> BY_CTR_DESC =
            Comparator.comparingDouble(Campaign::ctr).reversed().thenComparing(Campaign::id);

    /** Lowest CPA first; ties by campaign id so the output is deterministic. */
    static final Comparator<Campaign> BY_CPA_ASC =
            Comparator.comparingDouble(Campaign::cpa).thenComparing(Campaign::id);

    /** CTR is undefined without impressions. */
    boolean hasCtr() {
        return impressions > 0;
    }

    /** CPA is undefined without conversions. */
    boolean hasCpa() {
        return conversions > 0;
    }

    double ctr() {
        return (double) clicks / impressions;
    }

    double cpa() {
        return (double) spendCents / 100 / conversions;
    }

    BigDecimal spend() {
        return BigDecimal.valueOf(spendCents, 2);
    }

    /** CTR rounded half-up to 4 decimals, or {@code null} if undefined. */
    BigDecimal roundedCtr() {
        return hasCtr()
                ? BigDecimal.valueOf(clicks).divide(BigDecimal.valueOf(impressions), 4, RoundingMode.HALF_UP)
                : null;
    }

    /** CPA rounded half-up to 2 decimals, or {@code null} if undefined. */
    BigDecimal roundedCpa() {
        return hasCpa() ? spend().divide(BigDecimal.valueOf(conversions), 2, RoundingMode.HALF_UP) : null;
    }
}

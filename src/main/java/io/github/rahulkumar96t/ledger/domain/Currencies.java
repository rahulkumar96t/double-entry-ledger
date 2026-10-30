package io.github.rahulkumar96t.ledger.domain;

import java.util.Currency;
import java.util.Optional;
import java.util.regex.Pattern;

/** ISO-4217 helpers. Only currencies with a defined minor unit can be booked in integer minor units. */
public final class Currencies {

    private static final Pattern CODE = Pattern.compile("[A-Z]{3}");

    private Currencies() {}

    /** Returns the number of minor-unit digits (EUR 2, JPY 0, KWD 3), or empty if the code is not bookable. */
    public static Optional<Integer> minorUnits(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            return Optional.empty();
        }
        try {
            int digits = Currency.getInstance(code).getDefaultFractionDigits();
            // Codes such as XAU (gold) or XXX (no currency) have no minor unit.
            return digits < 0 ? Optional.empty() : Optional.of(digits);
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    public static boolean isBookable(String code) {
        return minorUnits(code).isPresent();
    }
}

package io.github.rahulkumar96t.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rahulkumar96t.ledger.domain.LedgerRuleViolation.Rule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PostingRulesTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    private static PostingLine line(UUID account, String currency, long amount) {
        return new PostingLine(account, currency, amount);
    }

    private static Rule violatedRule(Runnable action) {
        try {
            action.run();
        } catch (LedgerRuleViolation e) {
            return e.rule();
        }
        throw new AssertionError("expected a LedgerRuleViolation");
    }

    @Nested
    class Shape {

        @Test
        void acceptsASimpleBalancedEntry() {
            assertThatCode(() -> PostingRules.validateShape(List.of(line(A, "EUR", -1250), line(B, "EUR", 1250))))
                    .doesNotThrowAnyException();
        }

        @Test
        void acceptsSplitsAcrossManyAccounts() {
            assertThatCode(() -> PostingRules.validateShape(List.of(
                            line(A, "EUR", -10_000),
                            line(B, "EUR", 7_000),
                            line(C, "EUR", 2_500),
                            line(D, "EUR", 500))))
                    .doesNotThrowAnyException();
        }

        @Test
        void balancesEachCurrencySeparately() {
            // An FX conversion: EUR leg and USD leg each net to zero.
            assertThatCode(() -> PostingRules.validateShape(List.of(
                            line(A, "EUR", -10_000),
                            line(B, "EUR", 10_000),
                            line(C, "USD", -10_850),
                            line(D, "USD", 10_850))))
                    .doesNotThrowAnyException();
        }

        @Test
        void rejectsEntriesThatOnlyBalanceAcrossCurrencies() {
            LedgerRuleViolation e = catchViolation(
                    () -> PostingRules.validateShape(List.of(line(A, "EUR", -10_000), line(B, "USD", 10_000))));
            assertThat(e.rule()).isEqualTo(Rule.UNBALANCED_ENTRY);
            assertThat(e.details().get("imbalances")).isEqualTo(Map.of("EUR", -10_000L, "USD", 10_000L));
        }

        @Test
        void rejectsAnUnbalancedEntryAndReportsTheDifference() {
            LedgerRuleViolation e = catchViolation(
                    () -> PostingRules.validateShape(List.of(line(A, "EUR", -1000), line(B, "EUR", 999))));
            assertThat(e.rule()).isEqualTo(Rule.UNBALANCED_ENTRY);
            assertThat(e.details().get("imbalances")).isEqualTo(Map.of("EUR", -1L));
        }

        @Test
        void requiresAtLeastTwoPostings() {
            assertThat(violatedRule(() -> PostingRules.validateShape(List.of(line(A, "EUR", 0)))))
                    .isEqualTo(Rule.TOO_FEW_POSTINGS);
            assertThat(violatedRule(() -> PostingRules.validateShape(List.of())))
                    .isEqualTo(Rule.TOO_FEW_POSTINGS);
            assertThat(violatedRule(() -> PostingRules.validateShape(null))).isEqualTo(Rule.TOO_FEW_POSTINGS);
        }

        @Test
        void capsTheNumberOfPostings() {
            List<PostingLine> lines = new ArrayList<>();
            IntStream.range(0, PostingRules.MAX_POSTINGS + 1)
                    .forEach(i -> lines.add(line(UUID.randomUUID(), "EUR", i == 0 ? -PostingRules.MAX_POSTINGS : 1)));
            assertThat(violatedRule(() -> PostingRules.validateShape(lines))).isEqualTo(Rule.TOO_MANY_POSTINGS);
        }

        @Test
        void rejectsZeroAmounts() {
            assertThat(violatedRule(() -> PostingRules.validateShape(
                            List.of(line(A, "EUR", 0), line(B, "EUR", 100), line(C, "EUR", -100)))))
                    .isEqualTo(Rule.ZERO_AMOUNT);
        }

        @ParameterizedTest
        @ValueSource(longs = {PostingRules.MAX_ABS_AMOUNT + 1, Long.MAX_VALUE, Long.MIN_VALUE})
        void rejectsAmountsOutsideTheSupportedRange(long amount) {
            assertThat(violatedRule(
                            () -> PostingRules.validateShape(List.of(line(A, "EUR", amount), line(B, "EUR", 1)))))
                    .isEqualTo(Rule.AMOUNT_OUT_OF_RANGE);
        }

        @Test
        void acceptsTheLargestSupportedAmount() {
            long max = PostingRules.MAX_ABS_AMOUNT;
            assertThatCode(() -> PostingRules.validateShape(List.of(line(A, "EUR", -max), line(B, "EUR", max))))
                    .doesNotThrowAnyException();
        }

        @Test
        void rejectsTheSameAccountTwice() {
            assertThat(violatedRule(() -> PostingRules.validateShape(
                            List.of(line(A, "EUR", -100), line(A, "EUR", 40), line(B, "EUR", 60)))))
                    .isEqualTo(Rule.DUPLICATE_ACCOUNT);
        }

        @ParameterizedTest
        @ValueSource(strings = {"eur", "EURO", "ZZZ", "XAU", "XXX", ""})
        void rejectsCodesThatAreNotBookableIsoCurrencies(String currency) {
            assertThat(violatedRule(
                            () -> PostingRules.validateShape(List.of(line(A, currency, -100), line(B, currency, 100)))))
                    .isEqualTo(Rule.UNSUPPORTED_CURRENCY);
        }

        @Test
        void randomBalancedEntriesPassAndAnySingleCentOffFails() {
            Random random = new Random(7);
            for (int round = 0; round < 500; round++) {
                List<PostingLine> lines = randomBalancedEntry(random);
                assertThatCode(() -> PostingRules.validateShape(lines)).doesNotThrowAnyException();

                List<PostingLine> broken = new ArrayList<>(lines);
                int victim = random.nextInt(broken.size());
                PostingLine original = broken.get(victim);
                long delta = random.nextBoolean() ? 1 : -1;
                long nudged = original.amount() + delta;
                if (nudged == 0) {
                    nudged += delta;
                }
                broken.set(victim, line(original.accountId(), original.currency(), nudged));
                assertThat(violatedRule(() -> PostingRules.validateShape(broken)))
                        .isEqualTo(Rule.UNBALANCED_ENTRY);
            }
        }

        private List<PostingLine> randomBalancedEntry(Random random) {
            List<PostingLine> lines = new ArrayList<>();
            for (String currency : random.nextBoolean() ? List.of("EUR") : List.of("EUR", "JPY")) {
                int legs = 2 + random.nextInt(5);
                long sum = 0;
                for (int i = 0; i < legs - 1; i++) {
                    long amount = (1 + random.nextInt(1_000_000)) * (random.nextBoolean() ? 1L : -1L);
                    sum += amount;
                    lines.add(line(UUID.randomUUID(), currency, amount));
                }
                if (sum == 0) {
                    // Keep the closing leg non-zero.
                    lines.add(line(UUID.randomUUID(), currency, 1));
                    sum = 1;
                }
                lines.add(line(UUID.randomUUID(), currency, -sum));
            }
            Collections.shuffle(lines, random);
            return lines;
        }
    }

    @Nested
    class Accounts {

        private final Account eurAsset = account(A, AccountType.ASSET, "EUR", false, 0);
        private final Account eurIncome = account(B, AccountType.REVENUE, "EUR", false, 0);

        @Test
        void acceptsPostingsInTheAccountCurrency() {
            assertThatCode(() -> PostingRules.checkAccounts(
                            List.of(line(A, "EUR", 100), line(B, "EUR", -100)), Map.of(A, eurAsset, B, eurIncome)))
                    .doesNotThrowAnyException();
        }

        @Test
        void rejectsUnknownAccounts() {
            LedgerRuleViolation e = catchViolation(() -> PostingRules.checkAccounts(
                    List.of(line(A, "EUR", 100), line(C, "EUR", -100)), Map.of(A, eurAsset)));
            assertThat(e.rule()).isEqualTo(Rule.UNKNOWN_ACCOUNT);
            assertThat(e.details()).containsEntry("accountId", C);
        }

        @Test
        void rejectsACurrencyThatDiffersFromTheAccount() {
            LedgerRuleViolation e = catchViolation(() -> PostingRules.checkAccounts(
                    List.of(line(A, "USD", 100), line(B, "USD", -100)), Map.of(A, eurAsset, B, eurIncome)));
            assertThat(e.rule()).isEqualTo(Rule.CURRENCY_MISMATCH);
            assertThat(e.details()).containsEntry("accountCurrency", "EUR").containsEntry("postingCurrency", "USD");
        }
    }

    @Nested
    class Funds {

        @ParameterizedTest(name = "{0} holding {1}, posting {2} -> {3}")
        @CsvSource({
            // Debit-normal: debits add funds, credits consume them.
            "ASSET,     500,  -500, 0",
            "ASSET,     500,   250, 750",
            "EXPENSE,     0,   100, 100",
            // Credit-normal: stored balance is negative when funded; credits add, debits consume.
            "LIABILITY, -500,  500, 0",
            "LIABILITY,    0, -300, -300",
            "EQUITY,       0, -100, -100",
            "REVENUE,   -100,  100, 0",
        })
        void allowsPostingsThatKeepTheNaturalBalanceNonNegative(
                AccountType type, long balance, long amount, long expected) {
            assertThat(PostingRules.apply(account(A, type, "EUR", false, balance), amount))
                    .isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} holding {1}, posting {2}")
        @CsvSource({"ASSET, 500, -501", "EXPENSE, 0, -1", "LIABILITY, -500, 501", "EQUITY, 0, 1", "REVENUE, 0, 1"})
        void rejectsOverdrafts(AccountType type, long balance, long amount) {
            LedgerRuleViolation e =
                    catchViolation(() -> PostingRules.apply(account(A, type, "EUR", false, balance), amount));
            assertThat(e.rule()).isEqualTo(Rule.INSUFFICIENT_FUNDS);
            assertThat(e.details()).containsEntry("available", type.naturalBalance(balance));
        }

        @Test
        void overdraftAccountsMayGoNegative() {
            assertThat(PostingRules.apply(account(A, AccountType.ASSET, "EUR", true, 0), -1_000))
                    .isEqualTo(-1_000);
        }

        @Test
        void detectsBalanceOverflow() {
            Account nearMax = account(A, AccountType.ASSET, "EUR", true, Long.MAX_VALUE - 5);
            assertThat(violatedRule(() -> PostingRules.apply(nearMax, 6))).isEqualTo(Rule.BALANCE_OVERFLOW);
            Account nearMin = account(A, AccountType.ASSET, "EUR", true, Long.MIN_VALUE + 5);
            assertThat(violatedRule(() -> PostingRules.apply(nearMin, -5))).isEqualTo(Rule.BALANCE_OVERFLOW);
        }
    }

    @Nested
    class Transfers {

        @Test
        void creditsTheSourceAndDebitsTheDestination() {
            assertThat(PostingRules.transfer(A, B, "EUR", 1250))
                    .containsExactly(line(A, "EUR", -1250), line(B, "EUR", 1250));
        }

        @ParameterizedTest
        @ValueSource(longs = {0, -1})
        void requiresAPositiveAmount(long amount) {
            assertThat(violatedRule(() -> PostingRules.transfer(A, B, "EUR", amount)))
                    .isEqualTo(Rule.NON_POSITIVE_TRANSFER);
        }

        @Test
        void aTransferToItselfIsRejectedAsADuplicateAccount() {
            assertThat(violatedRule(() -> PostingRules.validateShape(PostingRules.transfer(A, A, "EUR", 10))))
                    .isEqualTo(Rule.DUPLICATE_ACCOUNT);
        }
    }

    @Test
    void naturalBalanceFlipsCreditNormalAccounts() {
        assertThat(AccountType.ASSET.naturalBalance(-42)).isEqualTo(-42);
        assertThat(AccountType.EXPENSE.naturalBalance(42)).isEqualTo(42);
        assertThat(AccountType.LIABILITY.naturalBalance(-42)).isEqualTo(42);
        assertThat(AccountType.EQUITY.naturalBalance(42)).isEqualTo(-42);
        assertThat(AccountType.REVENUE.normalBalance()).isEqualTo(AccountType.NormalBalance.CREDIT);
    }

    @Test
    void minorUnitsFollowIso4217() {
        assertThat(Currencies.minorUnits("EUR")).contains(2);
        assertThat(Currencies.minorUnits("JPY")).contains(0);
        assertThat(Currencies.minorUnits("KWD")).contains(3);
        assertThat(Currencies.minorUnits("XAU")).isEmpty();
        assertThat(Currencies.minorUnits("usd")).isEmpty();
        assertThat(Currencies.minorUnits(null)).isEmpty();
    }

    private static Account account(UUID id, AccountType type, String currency, boolean allowNegative, long balance) {
        return new Account(id, "code-" + id, "name", type, currency, allowNegative, balance, 0, Instant.EPOCH);
    }

    private static LedgerRuleViolation catchViolation(Runnable action) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(action::run);
        assertThat(thrown).isInstanceOf(LedgerRuleViolation.class);
        return (LedgerRuleViolation) thrown;
    }

    @Test
    void violationDetailsAreImmutable() {
        LedgerRuleViolation e = new LedgerRuleViolation(Rule.ZERO_AMOUNT, "x", Map.of("a", 1));
        assertThatThrownBy(() -> e.details().put("b", 2)).isInstanceOf(UnsupportedOperationException.class);
    }
}

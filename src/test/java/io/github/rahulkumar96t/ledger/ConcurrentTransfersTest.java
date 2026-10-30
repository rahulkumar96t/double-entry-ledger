package io.github.rahulkumar96t.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rahulkumar96t.ledger.domain.AccountType;
import io.github.rahulkumar96t.ledger.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Fires transfers at a real embedded Tomcat from many threads and then audits the database: per-currency sums,
 * per-account balances against the successful responses, and every account's balance_after chain.
 */
class ConcurrentTransfersTest extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentTransfersTest.class);
    private static final int THREADS = 16;
    private static final int TRANSFERS = 600;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MeterRegistry meters;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    record Transfer(int index, UUID from, UUID to, String currency, long amount, String key, String description) {}

    record Outcome(Transfer transfer, int status, String entryId, String problemType) {}

    @Test
    void parallelTransfersKeepEveryCurrencyBalancedAndLoseNoUpdates() throws Exception {
        String tag = "concurrency-" + UUID.randomUUID();
        Map<String, List<UUID>> accounts = new LinkedHashMap<>();
        Map<UUID, Long> expected = new HashMap<>();
        for (int i = 0; i < 6; i++) {
            UUID account = fundedAccount("EUR", 20_000);
            accounts.computeIfAbsent("EUR", c -> new ArrayList<>()).add(account);
            expected.put(account, 20_000L);
        }
        for (int i = 0; i < 3; i++) {
            UUID account = fundedAccount("JPY", 5_000);
            accounts.computeIfAbsent("JPY", c -> new ArrayList<>()).add(account);
            expected.put(account, 5_000L);
        }

        Random random = new Random(20261030);
        List<Transfer> plan = new ArrayList<>();
        for (int i = 0; i < TRANSFERS; i++) {
            String currency = random.nextInt(3) == 0 ? "JPY" : "EUR";
            List<UUID> candidates = accounts.get(currency);
            UUID from = candidates.get(random.nextInt(candidates.size()));
            UUID to;
            do {
                to = candidates.get(random.nextInt(candidates.size()));
            } while (to.equals(from));
            long amount = 1 + random.nextInt(currency.equals("EUR") ? 3_000 : 800);
            // Every tenth transfer carries an Idempotency-Key and is submitted twice, like a client retry.
            String key = i % 10 == 0 ? tag + "-" + i : null;
            plan.add(new Transfer(i, from, to, currency, amount, key, tag));
        }
        List<Transfer> submissions = new ArrayList<>(plan);
        plan.stream().filter(t -> t.key() != null).forEach(submissions::add);
        Collections.shuffle(submissions, random);

        double retriesBefore = meters.counter("ledger.optimistic.retries").count();
        List<Outcome> outcomes = runConcurrently(submissions);
        long retries = (long) (meters.counter("ledger.optimistic.retries").count() - retriesBefore);

        Map<Integer, Set<String>> entriesByTransfer = new HashMap<>();
        Map<Integer, Integer> statusCounts = new HashMap<>();
        for (Outcome outcome : outcomes) {
            statusCounts.merge(outcome.status(), 1, Integer::sum);
            switch (outcome.status()) {
                case 201 ->
                    entriesByTransfer
                            .computeIfAbsent(outcome.transfer().index(), i -> new HashSet<>())
                            .add(outcome.entryId());
                case 422 -> assertThat(outcome.problemType()).isEqualTo("/problems/insufficient-funds");
                case 409 ->
                    assertThat(outcome.problemType())
                            .isIn("/problems/concurrent-modification", "/problems/idempotency-key-in-progress");
                default -> fail("Unexpected HTTP " + outcome.status() + " for " + outcome.transfer());
            }
        }
        // A duplicate submission with the same key never creates a second entry.
        entriesByTransfer.values().forEach(ids -> assertThat(ids).hasSize(1));

        for (int index : entriesByTransfer.keySet()) {
            Transfer t = plan.get(index);
            expected.merge(t.from(), -t.amount(), Long::sum);
            expected.merge(t.to(), t.amount(), Long::sum);
        }

        // No lost updates: every balance equals its opening balance plus exactly the acknowledged transfers.
        expected.forEach((account, balance) ->
                assertThat(balance(account)).as("balance of %s", account).isEqualTo(balance));
        long entries = jdbc.sql("SELECT COUNT(*) FROM journal_entries WHERE description = :tag")
                .param("tag", tag)
                .query(Long.class)
                .single();
        assertThat(entries).isEqualTo(entriesByTransfer.size());

        // Each account's postings form an unbroken running balance that never dipped below zero.
        for (UUID account : expected.keySet()) {
            assertRunningBalance(account);
        }
        assertEveryCurrencySumsToZero();

        log.info(
                "{} submissions over {} threads: statuses {}, {} transfers applied, {} optimistic retries",
                submissions.size(),
                THREADS,
                statusCounts,
                entriesByTransfer.size(),
                retries);
        assertThat(entriesByTransfer.size()).isGreaterThan(TRANSFERS / 2);
    }

    @Test
    void concurrentWithdrawalsCannotOverdrawAnAccount() throws Exception {
        UUID wallet = fundedAccount("EUR", 1_000);
        UUID shop = openAccount(AccountType.ASSET, "EUR", false);
        String tag = "double-spend-" + UUID.randomUUID();
        List<Transfer> attempts = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            attempts.add(new Transfer(i, wallet, shop, "EUR", 100, null, tag));
        }

        List<Outcome> outcomes = runConcurrently(attempts);

        assertThat(outcomes).filteredOn(o -> o.status() == 201).hasSize(10);
        assertThat(outcomes)
                .filteredOn(o -> o.status() == 422)
                .hasSize(40)
                .allSatisfy(o -> assertThat(o.problemType()).isEqualTo("/problems/insufficient-funds"));
        assertThat(balance(wallet)).isZero();
        assertThat(balance(shop)).isEqualTo(1_000);
        assertRunningBalance(wallet);
    }

    @Test
    void concurrentRetriesWithOneKeyPostExactlyOnce() throws Exception {
        UUID from = fundedAccount("EUR", 10_000);
        UUID to = openAccount(AccountType.ASSET, "EUR", false);
        String tag = "same-key-" + UUID.randomUUID();
        List<Transfer> copies = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            copies.add(new Transfer(0, from, to, "EUR", 250, tag, tag));
        }

        List<Outcome> outcomes = runConcurrently(copies);

        assertThat(outcomes).allSatisfy(o -> assertThat(o.status()).isIn(201, 409));
        assertThat(outcomes)
                .filteredOn(o -> o.status() == 409)
                .allSatisfy(o -> assertThat(o.problemType()).isEqualTo("/problems/idempotency-key-in-progress"));
        assertThat(outcomes.stream()
                        .filter(o -> o.status() == 201)
                        .map(Outcome::entryId)
                        .distinct())
                .hasSize(1);
        assertThat(balance(to)).isEqualTo(250);
        assertThat(balance(from)).isEqualTo(9_750);
    }

    private List<Outcome> runConcurrently(List<Transfer> transfers) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (Transfer transfer : transfers) {
            Callable<Outcome> call = () -> {
                start.await();
                return send(transfer);
            };
            futures.add(pool.submit(call));
        }
        start.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) {
            outcomes.add(future.get(2, TimeUnit.MINUTES));
        }
        return outcomes;
    }

    private Outcome send(Transfer t) throws Exception {
        Map<String, Object> body = transferBody(t.from(), t.to(), t.currency(), t.amount());
        body.put("description", t.description());
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/transfers"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(body)));
        if (t.key() != null) {
            request.header("Idempotency-Key", t.key());
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = this.json.readTree(response.body());
        return new Outcome(
                t,
                response.statusCode(),
                response.statusCode() == 201 ? json.get("id").asText() : null,
                response.statusCode() >= 400 ? json.path("type").asText() : null);
    }

    private void assertRunningBalance(UUID account) {
        List<long[]> postings = jdbc.sql(
                        "SELECT amount, balance_after FROM postings WHERE account_id = :id ORDER BY id")
                .param("id", account)
                .query((rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)})
                .list();
        long running = 0;
        for (long[] posting : postings) {
            running += posting[0];
            assertThat(posting[1]).as("balance_after of %s", account).isEqualTo(running);
            assertThat(running).as("overdraft on %s", account).isGreaterThanOrEqualTo(0);
        }
        assertThat(running).isEqualTo(balance(account));
    }

    private void assertEveryCurrencySumsToZero() {
        Map<String, Long> sums = new HashMap<>();
        jdbc.sql("SELECT currency, CAST(SUM(balance) AS BIGINT) AS total FROM accounts GROUP BY currency")
                .query((rs, row) -> Map.entry(rs.getString("currency"), rs.getLong("total")))
                .list()
                .forEach(e -> sums.put(e.getKey(), e.getValue()));
        assertThat(sums)
                .isNotEmpty()
                .allSatisfy((currency, total) -> assertThat(total).as(currency).isZero());

        JsonNode trialBalance = body(get("/api/v1/trial-balance"));
        assertThat(trialBalance.get("balanced").asBoolean()).isTrue();
    }
}

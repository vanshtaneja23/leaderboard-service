package dev.vansh.leaderboard.ranking;

import dev.vansh.leaderboard.core.RankingEntry;
import dev.vansh.leaderboard.store.ScoreStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rebuilds Redis from Postgres. Redis holds nothing that Postgres doesn't, so losing it (restart
 * without persistence, eviction, failover) only costs a rebuild.
 *
 * <p>Detection: a leaderboard needs a rebuild when its Redis key has <em>fewer</em> members than
 * Postgres has entries. That catches both an empty Redis and a partially refilled one (live writes
 * landed on a fresh Redis before a rebuild ran), which a simple "key exists?" check would miss.
 *
 * <p>Safety: the rebuild writes with ZADD GT, the same max-wins operation as live traffic, so it can
 * run concurrently with submissions, and on several replicas at once, without locks or downtime.
 */
@Component
public class RankingRebuilder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RankingRebuilder.class);
    private static final int BATCH_SIZE = 1_000;

    private final ScoreStore store;
    private final RankingStore ranking;
    private final Counter rebuilds;

    public RankingRebuilder(ScoreStore store, RankingStore ranking, MeterRegistry meters) {
        this.store = store;
        this.ranking = ranking;
        // Should stay at zero in steady state; alert if it moves (Redis lost data).
        this.rebuilds = Counter.builder("leaderboard.rebuilds")
                .description("Leaderboards rebuilt from Postgres because Redis was missing members")
                .register(meters);
    }

    /**
     * Runs once at startup, before Spring Boot marks the app ready, so Kubernetes does not route
     * traffic to this pod until rankings are complete. If Redis is unreachable this throws and the
     * app fails to start (fail fast) rather than serving empty leaderboards.
     */
    @Override
    public void run(ApplicationArguments args) {
        int rebuilt = reconcile();
        log.info("startup ranking check complete, rebuilt {} leaderboard(s)", rebuilt);
    }

    /** Also runs periodically, which covers Redis restarting while the service keeps running. */
    @Scheduled(initialDelayString = "${leaderboard.reconcile.interval:60s}",
            fixedDelayString = "${leaderboard.reconcile.interval:60s}")
    public void scheduledReconcile() {
        try {
            reconcile();
        } catch (RuntimeException e) {
            log.warn("periodic ranking reconcile failed; will retry next interval", e);
        }
    }

    /** @return number of leaderboards that were rebuilt. */
    public int reconcile() {
        int rebuilt = 0;
        for (Map.Entry<String, Long> board : store.countEntriesByLeaderboard().entrySet()) {
            String leaderboardId = board.getKey();
            long expected = board.getValue();
            long actual = ranking.size(leaderboardId);
            if (actual < expected) {
                log.warn("leaderboard '{}' has {} of {} members in Redis; rebuilding from Postgres",
                        leaderboardId, actual, expected);
                rebuild(leaderboardId);
                rebuilds.increment();
                rebuilt++;
            }
        }
        return rebuilt;
    }

    /** Streams entries from Postgres and writes them to Redis in batches of {@value #BATCH_SIZE}. */
    public void rebuild(String leaderboardId) {
        List<RankingEntry> batch = new ArrayList<>(BATCH_SIZE);
        long[] total = {0};
        store.forEachEntry(leaderboardId, entry -> {
            batch.add(entry);
            if (batch.size() == BATCH_SIZE) {
                ranking.addAll(leaderboardId, batch);
                total[0] += batch.size();
                batch.clear();
            }
        });
        ranking.addAll(leaderboardId, batch);
        total[0] += batch.size();
        log.info("rebuilt leaderboard '{}' with {} entries", leaderboardId, total[0]);
    }
}

package dev.vansh.leaderboard.ranking;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.vansh.leaderboard.core.RankScore;
import dev.vansh.leaderboard.core.RankWindow;
import dev.vansh.leaderboard.core.RankedPlayer;
import dev.vansh.leaderboard.core.RankingEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.connection.RedisZSetCommands.ZAddArgs;
import org.springframework.data.redis.connection.zset.Tuple;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis sorted-set index of each leaderboard: one key per leaderboard, member = player id,
 * score = {@link RankScore#encode composite of score and event id}.
 *
 * <p>Every write is {@code ZADD ... GT}: Redis only replaces a member's score if the new one is
 * greater. That makes writes commutative and idempotent ("keep the max"), so concurrent writers,
 * retries, out-of-order arrivals and a full rebuild racing live traffic all converge to the same
 * state without any locking.
 */
@Component
public class RankingStore {

    static final String KEY_PREFIX = "leaderboard:";

    /**
     * Rank plus neighbors in one atomic step. Doing ZREVRANK and ZREVRANGE as two separate calls
     * would let another write shift ranks in between, returning a window that doesn't match the rank.
     * Returns {} if the player is absent, else {rank, windowStart, member1, score1, member2, ...}.
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RANK_WINDOW = RedisScript.of("""
            local rank = redis.call('ZREVRANK', KEYS[1], ARGV[1])
            if not rank then return {} end
            local k = tonumber(ARGV[2])
            local first = math.max(0, rank - k)
            local window = redis.call('ZREVRANGE', KEYS[1], first, rank + k, 'WITHSCORES')
            local out = {rank, first}
            for i = 1, #window do out[#out + 1] = window[i] end
            return out
            """, List.class);

    private final StringRedisTemplate redis;

    public RankingStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public static String key(String leaderboardId) {
        return KEY_PREFIX + leaderboardId;
    }

    /** ZADD key GT composite player: no-op unless this entry ranks above the stored one. */
    public void add(RankingEntry entry) {
        double composite = RankScore.encode(entry.score(), entry.eventId());
        redis.execute((RedisCallback<Boolean>) connection -> connection.zSetCommands().zAdd(
                key(entry.leaderboardId()).getBytes(UTF_8),
                composite,
                entry.playerId().getBytes(UTF_8),
                ZAddArgs.empty().gt()));
    }

    /** Bulk ZADD GT in a single command; used by the cache rebuild. */
    public void addAll(String leaderboardId, Collection<RankingEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        Set<Tuple> tuples = new LinkedHashSet<>();
        for (RankingEntry e : entries) {
            tuples.add(Tuple.of(e.playerId().getBytes(UTF_8), RankScore.encode(e.score(), e.eventId())));
        }
        redis.execute((RedisCallback<Long>) connection -> connection.zSetCommands().zAdd(
                key(leaderboardId).getBytes(UTF_8), tuples, ZAddArgs.empty().gt()));
    }

    public List<RankedPlayer> topN(String leaderboardId, int n) {
        Set<TypedTuple<String>> rows = redis.opsForZSet().reverseRangeWithScores(key(leaderboardId), 0, n - 1);
        List<RankedPlayer> result = new ArrayList<>(rows.size());
        long rank = 1;
        for (TypedTuple<String> row : rows) {
            result.add(new RankedPlayer(rank++, row.getValue(), RankScore.decodeScore(row.getScore())));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public Optional<RankWindow> rankWindow(String leaderboardId, String playerId, int neighbors) {
        List<Object> reply = redis.execute(RANK_WINDOW, List.of(key(leaderboardId)),
                playerId, Integer.toString(neighbors));
        if (reply == null || reply.isEmpty()) {
            return Optional.empty();
        }
        long playerRank = (Long) reply.get(0);
        long windowStart = (Long) reply.get(1);

        RankedPlayer player = null;
        List<RankedPlayer> above = new ArrayList<>();
        List<RankedPlayer> below = new ArrayList<>();
        for (int i = 2; i < reply.size(); i += 2) {
            long zeroBasedRank = windowStart + (i - 2) / 2;
            String member = (String) reply.get(i);
            long score = RankScore.decodeScore(Double.parseDouble((String) reply.get(i + 1)));
            RankedPlayer rp = new RankedPlayer(zeroBasedRank + 1, member, score);
            if (zeroBasedRank < playerRank) {
                above.add(rp);
            } else if (zeroBasedRank == playerRank) {
                player = rp;
            } else {
                below.add(rp);
            }
        }
        return Optional.of(new RankWindow(player, above, below));
    }

    public boolean exists(String leaderboardId) {
        return Boolean.TRUE.equals(redis.hasKey(key(leaderboardId)));
    }

    public long size(String leaderboardId) {
        Long size = redis.opsForZSet().zCard(key(leaderboardId));
        return size == null ? 0 : size;
    }
}

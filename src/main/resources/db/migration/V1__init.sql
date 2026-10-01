-- Source of truth for the leaderboard service. Redis holds only a derived, rebuildable index.

-- Append-only log of every accepted submission.
-- `id` doubles as the tie-breaker: a lower id means an earlier submission.
CREATE TABLE score_events (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    player_id       VARCHAR(64)  NOT NULL,
    leaderboard_id  VARCHAR(64)  NOT NULL,
    score           BIGINT       NOT NULL CHECK (score >= 0),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Idempotency is enforced here, by the database, not by application code:
    -- two concurrent requests with the same key cannot both insert.
    CONSTRAINT uq_score_events_idempotency_key UNIQUE (idempotency_key)
);

-- One row per (leaderboard, player): the submission that currently ranks the player.
-- This is exactly what the Redis sorted set mirrors, and what a cache rebuild reads.
CREATE TABLE leaderboard_entries (
    leaderboard_id VARCHAR(64) NOT NULL,
    player_id      VARCHAR(64) NOT NULL,
    best_score     BIGINT      NOT NULL,
    best_event_id  BIGINT      NOT NULL REFERENCES score_events (id),
    updated_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (leaderboard_id, player_id)
);

-- Lifetime stats per player, across all leaderboards.
CREATE TABLE player_stats (
    player_id    VARCHAR(64) PRIMARY KEY,
    games_played BIGINT      NOT NULL,
    best_score   BIGINT      NOT NULL,
    last_updated TIMESTAMPTZ NOT NULL
);

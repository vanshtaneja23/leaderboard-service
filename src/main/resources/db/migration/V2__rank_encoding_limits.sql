-- Redis ranks by a composite of (score, event id) packed into the 53 exact-integer bits of a double
-- (see RankScore.java): 26 bits of score, 27 bits of event id. Enforce the same limits here so a
-- value that cannot be ranked correctly is rejected inside the transaction, before it is committed,
-- instead of being silently mis-ranked later.
ALTER TABLE score_events
    ADD CONSTRAINT ck_score_events_score_fits_rank_encoding CHECK (score <= 67108863);

ALTER TABLE score_events
    ADD CONSTRAINT ck_score_events_id_fits_rank_encoding CHECK (id <= 134217727);

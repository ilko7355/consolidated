-- ============================================================================
-- DEPRECATED - read-only diagnostic only. Does NOT change your schema.
-- ============================================================================
-- This script used to be a full, standalone migration that duplicated the
-- logic now owned by the authoritative migration:
--
--     docs/migrations/V001__participants_tournament_relationship.sql
--
-- That duplication was a maintenance risk (two implementations of the same
-- schema change could quietly drift apart), so this file no longer performs
-- any INSERT/UPDATE/DELETE/ALTER/DROP/CREATE statement - every statement
-- below is a read-only SELECT, safe to run as many times as you like against
-- any database state, and it never touches your data or schema.
--
-- It is kept only as an optional pre-check for a local database that still
-- has (or might still have) the legacy `tournament_participants` join table
-- and/or a partially-added `participants.tournament_id` column, to help you
-- see - in plain rows, not just a count - exactly which participants need
-- manual attention before you run V001.
--
-- WHAT TO ACTUALLY RUN
-- Always run docs/migrations/V001__participants_tournament_relationship.sql
-- to perform the real migration, in every environment (local, staging,
-- production). This script is an optional aid you can run BEFORE that, only
-- if you have local legacy data you're unsure about. If your local database
-- is empty/disposable, skip this file entirely - just drop the database and
-- let the app recreate the schema on next startup.
--
-- SCHEMA-STATE SAFETY
-- This script can be run against any of these schema states without error,
-- using the same "check information_schema, then PREPARE/EXECUTE the right
-- statement" technique V001 uses for its own ALTER statements:
--   - old schema:            participants has no tournament_id column yet
--   - partially migrated:    participants.tournament_id exists but is only
--                             set on some rows (the rest are still NULL)
--   - fully migrated:        participants.tournament_id exists and is set on
--                             every row (tournament_participants may or may
--                             not have been dropped yet)
--   - legacy table present or already dropped, independently of the above
-- Each check below first looks at information_schema to see which of
-- `tournament_participants` and `participants.tournament_id` actually exist
-- in THIS database, then runs the version of the check that matches what it
-- found - never a version that references something that isn't there. If a
-- check has nothing meaningful to look at for the current schema state (e.g.
-- neither the legacy table nor the new column exist), it reports that
-- plainly instead of guessing or erroring.
--
-- SHARED SESSION-STATE SAFETY (relevant if you run this in the same MySQL
-- session as V001, in either order)
-- Both this script and V001 use the same names for a few things: the
-- session variables `@schema` and `@has_join_table`, and the prepared
-- statement handle `stmt`. This is safe, not accidental reuse to worry
-- about:
--   - `@schema` and `@has_join_table` are always (re)computed from
--     information_schema as the very first thing each script does, before
--     either script reads them - so whichever script ran last, or whatever
--     was in those variables before, is irrelevant; each script starts by
--     overwriting them with the true current state of THIS database.
--   - Every `PREPARE stmt` in both scripts is immediately followed by its
--     own `EXECUTE stmt; DEALLOCATE PREPARE stmt;` on the same line, with no
--     exceptions (checked: 3 PREPARE/DEALLOCATE pairs here, 9 in V001) - so
--     `stmt` is never left allocated between statements.
--   - The one edge case this does NOT cover: if a previous script run in
--     this same session was interrupted by an unrelated error partway
--     through a PREPARE/EXECUTE/DEALLOCATE group (before reaching its
--     DEALLOCATE), `stmt` could be left allocated, and the next `PREPARE
--     stmt` - in either script - would then fail with "prepared statement
--     'stmt' already exists". If a prior run errored out, start a fresh
--     session before re-running either script, rather than continuing in
--     the same one.
-- ============================================================================

SET @schema = DATABASE();

SET @has_join_table := (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE table_schema = @schema AND table_name = 'tournament_participants'
);

SET @has_tournament_id_column := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE table_schema = @schema AND table_name = 'participants' AND column_name = 'tournament_id'
);

-- Check 0: what does this database's schema actually look like right now?
-- Read this first - it tells you which of the checks below will have
-- anything to report.
SELECT @has_join_table         AS legacy_tournament_participants_table_present,
       @has_tournament_id_column AS participants_tournament_id_column_present;

-- Check 1: participants with an ambiguous or invalid legacy relationship,
-- based purely on the old join table. V001 deliberately leaves these
-- participants' tournament_id NULL rather than guessing, and will not add
-- the NOT NULL/foreign-key/unique constraints until every participant is
-- cleanly resolved. Each row below is a participant_id that has one of these
-- problems:
--   - linked to more than one distinct tournament in the old join table
--   - referenced by a join row whose participant no longer exists
--   - referenced by a join row whose tournament no longer exists
-- Resolve these manually (pick/set the correct tournament, or remove the bad
-- row) before running V001, if you want a fully-constrained result on the
-- first attempt. If you leave them, V001 still runs safely - it just reports
-- these rows and leaves the schema nullable until they're fixed.
-- Skipped entirely if the legacy join table no longer exists - there is
-- nothing to check.
SET @check_1_sql := IF(
    @has_join_table = 1,
    'SELECT tp.participant_id,
            COUNT(DISTINCT tp.tournament_id) AS distinct_tournament_links,
            COUNT(*)                         AS join_rows,
            COUNT(p.id)                      AS participant_rows_found,
            COUNT(t.id)                      AS tournament_rows_found
     FROM tournament_participants tp
     LEFT JOIN participants p ON p.id = tp.participant_id
     LEFT JOIN tournaments t ON t.id = tp.tournament_id
     GROUP BY tp.participant_id
     HAVING COUNT(DISTINCT tp.tournament_id) <> 1
         OR COUNT(p.id) <> COUNT(*)
         OR COUNT(t.id) <> COUNT(*)',
    'SELECT ''tournament_participants does not exist - Check 1 skipped, nothing to check'' AS check_1_skipped'
);
PREPARE stmt FROM @check_1_sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Check 1b: participants whose existing direct `tournament_id` disagrees
-- with what the old join table says - a sign a partial migration and the
-- legacy data have drifted apart. Only meaningful (and only runs) when BOTH
-- the legacy join table and the new `tournament_id` column exist side by
-- side - i.e. a partially migrated database (CASE B). On an old schema with
-- no `tournament_id` column yet, or a fully migrated one where the join
-- table has already been dropped, there is nothing to compare and this is
-- skipped with an explanatory message instead of erroring.
SET @check_1b_sql := IF(
    @has_join_table = 1 AND @has_tournament_id_column = 1,
    'SELECT tp.participant_id, p.tournament_id AS direct_link, MIN(tp.tournament_id) AS legacy_link
     FROM tournament_participants tp
     JOIN participants p ON p.id = tp.participant_id
     WHERE p.tournament_id IS NOT NULL
     GROUP BY tp.participant_id, p.tournament_id
     HAVING COUNT(DISTINCT tp.tournament_id) = 1 AND p.tournament_id <> MIN(tp.tournament_id)',
    'SELECT ''Check 1b skipped: needs both tournament_participants and participants.tournament_id to exist'' AS check_1b_skipped'
);
PREPARE stmt FROM @check_1b_sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Check 2: participant names that would collide once grouped by their
-- effective tournament. "Effective tournament" means: the direct
-- `participants.tournament_id` if that column exists and is already set for
-- a row (this is the authoritative value once present, exactly like the
-- application and V001 treat it), otherwise the single distinct tournament
-- that participant is linked to in the old join table (the same derivation
-- V001 uses for its own backfill). V001 will not add the case-insensitive
-- unique constraint while duplicates like this exist. Rename or remove one
-- of each pair first. This check adapts to all four combinations of
-- "column exists" x "join table exists" so it is accurate whether the
-- database is on the old schema, partially migrated, or fully migrated.
--
-- RELATIONSHIP TO CHECK 1b: only the partially-migrated branch below can
-- ever have both a direct tournament_id AND a legacy join-table link for
-- the same participant, so it's the only branch where "which one is right"
-- is even a question - the fully-migrated and old-schema branches each see
-- only one source of truth and have nothing to disagree with. In that
-- partially-migrated branch, picking `COALESCE(p.tournament_id, ...)` is
-- NOT a claim that the direct value is trustworthy - it's just the same
-- "direct wins if present" rule V001 itself follows. If a participant's
-- direct value actually disagrees with their legacy link, Check 1b already
-- reports that participant_id in detail, and this check's
-- `check_1b_mismatch_count` column below tells you, per duplicate-name
-- group, how many of its members have exactly that unresolved disagreement
-- - so a nonzero count here is a signal to go read Check 1b's rows for
-- those participants before trusting which tournament they were grouped
-- under, rather than something this check silently resolves on its own.
SET @check_2_sql := CASE
    WHEN @has_tournament_id_column = 1 AND @has_join_table = 1 THEN
        -- Partially migrated, legacy table still present: prefer each
        -- participant's own direct tournament_id where already set, and
        -- fall back to the legacy join table only for rows not yet
        -- backfilled - same rule as above, made explicit per-row via the
        -- direct_only/legacy_only/match/check_1b_mismatch breakdown so the
        -- output doesn't need to be read alongside Check 1b to be understood.
        'SELECT COALESCE(p.tournament_id, legacy.tournament_id) AS tournament_id,
                LOWER(p.name) AS name_lower,
                COUNT(*) AS occurrences,
                SUM(CASE WHEN p.tournament_id IS NOT NULL AND legacy.tournament_id IS NULL THEN 1 ELSE 0 END) AS direct_only_count,
                SUM(CASE WHEN p.tournament_id IS NULL AND legacy.tournament_id IS NOT NULL THEN 1 ELSE 0 END) AS legacy_only_count,
                SUM(CASE WHEN p.tournament_id IS NOT NULL AND legacy.tournament_id IS NOT NULL AND p.tournament_id = legacy.tournament_id THEN 1 ELSE 0 END) AS direct_legacy_match_count,
                SUM(CASE WHEN p.tournament_id IS NOT NULL AND legacy.tournament_id IS NOT NULL AND p.tournament_id <> legacy.tournament_id THEN 1 ELSE 0 END) AS check_1b_mismatch_count
         FROM participants p
         LEFT JOIN (
             SELECT tp.participant_id, MIN(tp.tournament_id) AS tournament_id
             FROM tournament_participants tp
             GROUP BY tp.participant_id
             HAVING COUNT(DISTINCT tp.tournament_id) = 1
         ) legacy ON legacy.participant_id = p.id
         WHERE COALESCE(p.tournament_id, legacy.tournament_id) IS NOT NULL
         GROUP BY COALESCE(p.tournament_id, legacy.tournament_id), LOWER(p.name)
         HAVING COUNT(*) > 1'
    WHEN @has_tournament_id_column = 1 AND @has_join_table = 0 THEN
        -- Fully migrated (or the legacy table was already dropped): the
        -- direct column is the only source of truth, so there is no legacy
        -- value to compare against - no mismatch is possible here, and this
        -- branch has no check_1b_mismatch_count column for that reason.
        'SELECT p.tournament_id, LOWER(p.name) AS name_lower, COUNT(*) AS occurrences
         FROM participants p
         WHERE p.tournament_id IS NOT NULL
         GROUP BY p.tournament_id, LOWER(p.name)
         HAVING COUNT(*) > 1'
    WHEN @has_tournament_id_column = 0 AND @has_join_table = 1 THEN
        -- Old schema: the legacy join table is the only source of truth, so
        -- again there is nothing to compare against - no mismatch possible.
        'SELECT derived.tournament_id, LOWER(p.name) AS name_lower, COUNT(*) AS occurrences
         FROM participants p
         JOIN (
             SELECT tp.participant_id, MIN(tp.tournament_id) AS tournament_id
             FROM tournament_participants tp
             GROUP BY tp.participant_id
             HAVING COUNT(DISTINCT tp.tournament_id) = 1
         ) derived ON derived.participant_id = p.id
         GROUP BY derived.tournament_id, LOWER(p.name)
         HAVING COUNT(*) > 1'
    ELSE
        -- Neither the column nor the legacy table exist: there is no way to
        -- know which tournament a participant belongs to, so there is
        -- nothing to group by.
        'SELECT ''Check 2 skipped: neither participants.tournament_id nor tournament_participants exist - nothing to group by'' AS check_2_skipped'
END;
PREPARE stmt FROM @check_2_sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

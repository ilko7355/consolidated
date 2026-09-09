-- Versioned migration V001: replace tournament_participants with a direct
-- Participant -> Tournament relationship and add case-insensitive name lookup.
-- Run against an existing application schema. Fresh databases are initialized
-- by the development profile; production databases must have the baseline
-- application schema before this migration is run.
--
-- This is the ONE authoritative script for this schema change, in every
-- environment (local, staging, production) - see the "Versioned schema
-- changes" section of README.md. Idempotent and safe to re-run: every step
-- checks whether it is already done before acting, and it only tightens
-- columns to NOT NULL / adds the foreign key and unique constraint / drops
-- the old join table once every participant has been cleanly resolved
-- (otherwise it leaves the schema nullable and reports what's unresolved).
--
-- If you have a local database with existing data in the legacy
-- `tournament_participants` join table and want to inspect any problem rows
-- in detail before running this, see the read-only diagnostic at
-- docs/migrate-participants-table.sql (deprecated as a migration - it no
-- longer changes your schema).

SET @schema = DATABASE();

SET @add_tournament_id := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE table_schema = @schema AND table_name = 'participants' AND column_name = 'tournament_id') = 0,
    'ALTER TABLE participants ADD COLUMN tournament_id BIGINT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @add_tournament_id; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_name_lower := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE table_schema = @schema AND table_name = 'participants' AND column_name = 'name_lower') = 0,
    'ALTER TABLE participants ADD COLUMN name_lower VARCHAR(100) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @add_name_lower; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_join_table := (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE table_schema = @schema AND table_name = 'tournament_participants'
);
SET @backfill := IF(
    @has_join_table = 1,
    'UPDATE participants p JOIN (SELECT tp.participant_id, MIN(tp.tournament_id) AS tournament_id FROM tournament_participants tp JOIN tournaments t ON t.id = tp.tournament_id GROUP BY tp.participant_id HAVING COUNT(DISTINCT tp.tournament_id) = 1) links ON links.participant_id = p.id SET p.tournament_id = links.tournament_id WHERE p.tournament_id IS NULL',
    'SELECT 1'
);
PREPARE stmt FROM @backfill; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE participants SET name_lower = LOWER(name) WHERE name_lower IS NULL;

SET @unlinked := (SELECT COUNT(*) FROM participants WHERE tournament_id IS NULL);
SET @duplicates := (
    SELECT COUNT(*) FROM (
        SELECT tournament_id, name_lower FROM participants
        GROUP BY tournament_id, name_lower HAVING COUNT(*) > 1
    ) invalid_duplicates
);
SET @legacy_issues := 0;
SET @legacy_validation := IF(
    @has_join_table = 1,
    'SELECT COUNT(*) INTO @legacy_issues FROM (SELECT tp.participant_id FROM tournament_participants tp LEFT JOIN participants p ON p.id = tp.participant_id LEFT JOIN tournaments t ON t.id = tp.tournament_id GROUP BY tp.participant_id HAVING COUNT(DISTINCT tp.tournament_id) <> 1 OR COUNT(p.id) <> COUNT(*) OR COUNT(t.id) <> COUNT(*) OR MAX(p.tournament_id) <> MIN(tp.tournament_id)) invalid_links',
    'SELECT 0 INTO @legacy_issues'
);
PREPARE stmt FROM @legacy_validation; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ready := @unlinked = 0 AND @duplicates = 0 AND @legacy_issues = 0;
SET @make_tournament_required := IF(@ready,
    'ALTER TABLE participants MODIFY COLUMN tournament_id BIGINT NOT NULL',
    'SELECT 1');
PREPARE stmt FROM @make_tournament_required; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @make_name_required := IF(@ready,
    'ALTER TABLE participants MODIFY COLUMN name_lower VARCHAR(100) NOT NULL',
    'SELECT 1');
PREPARE stmt FROM @make_name_required; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_fk := IF(@ready AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE table_schema = @schema AND table_name = 'participants' AND constraint_name = 'fk_participant_tournament') = 0,
    'ALTER TABLE participants ADD CONSTRAINT fk_participant_tournament FOREIGN KEY (tournament_id) REFERENCES tournaments(id)',
    'SELECT 1');
PREPARE stmt FROM @add_fk; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_unique := IF(@ready AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE table_schema = @schema AND table_name = 'participants' AND constraint_name = 'uk_participant_tournament_name') = 0,
    'ALTER TABLE participants ADD CONSTRAINT uk_participant_tournament_name UNIQUE (tournament_id, name_lower)',
    'SELECT 1');
PREPARE stmt FROM @add_unique; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @drop_join := IF(@ready AND @has_join_table = 1,
    'DROP TABLE tournament_participants',
    'SELECT 1');
PREPARE stmt FROM @drop_join; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- A nonzero @unlinked, @duplicates, or @legacy_issues intentionally leaves
-- the legacy table and nullable columns in place for manual remediation.
SELECT @unlinked AS unresolved_participants,
       @duplicates AS duplicate_names,
       @legacy_issues AS invalid_legacy_links,
       @ready AS migration_completed;

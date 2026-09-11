-- V104 inherited MySQL 8's default utf8mb4_0900_ai_ci, while the MTN
-- correlation registry explicitly uses utf8mb4_unicode_ci. Comparing those
-- columns prevents the legacy/shared recovery selector from executing (1267).
-- Align only the nullable, non-unique provider reference with that registry.
-- Preserve length, bytes, nullability, amounts, statuses and migration history.
-- Do not rewrite V104 or V116, or repair/delete the orphan staging V123 row.
ALTER TABLE provider_treasury_reservations
    MODIFY COLUMN provider_reference VARCHAR(191)
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL;

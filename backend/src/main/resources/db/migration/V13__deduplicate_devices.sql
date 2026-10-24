-- V13: Deduplicate devices keeping most recent by last_seen_at per (user_id, name, device_type)
DELETE FROM devices d
USING (
  SELECT id,
         ROW_NUMBER() OVER (
           PARTITION BY user_id, name, device_type
           ORDER BY last_seen_at DESC NULLS LAST, created_at DESC NULLS LAST, id DESC
         ) AS rn
  FROM devices
) dup
WHERE d.id = dup.id
  AND dup.rn > 1;

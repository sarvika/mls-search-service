-- Renames listings columns to match RESO Data Dictionary field names, kept as idiomatic
-- snake_case rather than literal PascalCase (e.g. mls_status, not MlsStatus) - Postgres
-- folds unquoted identifiers to lowercase, so literal RESO casing would need quoting on
-- every reference. geog/is_active/created_at are our own operational fields, not RESO
-- concepts, and are left as-is.
-- Verified against the official RESO Data Dictionary 2.0 spreadsheet (Fields/Lookups
-- sheets): StandardStatus is a constrained single-select field, restricted to the
-- StandardStatus Lookup's exact value list (Active, Active Under Contract, Canceled,
-- Closed, Coming Soon, Delete, Expired, Hold, Incomplete, Pending, Withdrawn).
-- MlsStatus is RESO's field for "a local or regional status... [that] must map to a
-- single StandardStatus" - i.e. the raw, unnormalized, source-system status string.
-- ListingIngestionService does a direct passthrough of the upstream status with no
-- normalization/mapping logic (and defaults to the literal "Unknown", which isn't even
-- in the StandardStatus lookup) - that's MlsStatus's definition, not StandardStatus's,
-- so this column is named accordingly.
ALTER TABLE listings RENAME COLUMN mls_source TO originating_system_name; -- RESO: OriginatingSystemName
ALTER TABLE listings RENAME COLUMN status TO mls_status;                  -- RESO: MlsStatus
ALTER TABLE listings RENAME COLUMN beds TO bedrooms_total;                -- RESO: BedroomsTotal
ALTER TABLE listings RENAME COLUMN address TO unparsed_address;          -- RESO: UnparsedAddress
ALTER TABLE listings RENAME COLUMN state TO state_or_province;           -- RESO: StateOrProvince
ALTER TABLE listings RENAME COLUMN zip TO postal_code;                   -- RESO: PostalCode
ALTER TABLE listings RENAME COLUMN photo_urls TO media;                  -- RESO: Media (kept as a flat URL array, not the full structured Media resource)

-- RESO splits bathroom count into BathroomsFull / BathroomsHalf rather than one combined
-- number. The old `baths` column only ever stored the ingestion service's combined
-- full+half*0.5 value - the true split was never persisted, so this backfill is a
-- best-effort approximation (half=1 if baths has a .5 fraction, else 0). The next
-- POST /listings/ingest/simplyrets run overwrites every row with the real full/half
-- values from the source, so this approximation is only visible until the next sync.
ALTER TABLE listings ADD COLUMN bathrooms_full SMALLINT;
ALTER TABLE listings ADD COLUMN bathrooms_half SMALLINT;
UPDATE listings SET
    bathrooms_full = FLOOR(baths)::SMALLINT,
    bathrooms_half = CASE WHEN baths - FLOOR(baths) >= 0.5 THEN 1 ELSE 0 END
WHERE baths IS NOT NULL;
ALTER TABLE listings DROP COLUMN baths;

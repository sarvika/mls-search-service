CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE listings (
    id                      BIGSERIAL PRIMARY KEY,
    mls_source              TEXT NOT NULL,
    listing_key             TEXT NOT NULL,
    status                  TEXT NOT NULL,
    list_price              NUMERIC(14, 2),
    beds                    SMALLINT,
    baths                   NUMERIC(3, 1),
    address                 TEXT,
    city                    TEXT,
    state                   TEXT,
    zip                     TEXT,
    latitude                DOUBLE PRECISION NOT NULL,
    longitude               DOUBLE PRECISION NOT NULL,
    geog                    GEOGRAPHY(Point, 4326) NOT NULL,
    photo_urls              JSONB NOT NULL DEFAULT '[]'::jsonb,
    is_active               BOOLEAN NOT NULL DEFAULT true,
    modification_timestamp  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (mls_source, listing_key)
);

CREATE INDEX idx_listings_geog ON listings USING GIST (geog);
CREATE INDEX idx_listings_active ON listings (status) WHERE is_active = true;
CREATE INDEX idx_listings_mod_ts ON listings (modification_timestamp);

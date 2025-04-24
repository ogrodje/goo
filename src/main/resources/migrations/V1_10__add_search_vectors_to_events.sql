ALTER TABLE events
    ADD COLUMN title_vec tsvector
        GENERATED ALWAYS AS (to_tsvector('english', title)) STORED;

CREATE INDEX title_vec_idx ON events USING GIN (title_vec);
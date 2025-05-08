ALTER TABLE meetups
    ADD COLUMN name_vec tsvector
        GENERATED ALWAYS AS (to_tsvector('english', name)) STORED;

CREATE INDEX name_vec_idx ON meetups USING GIN (name_vec);
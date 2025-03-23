CREATE TABLE IF NOT EXISTS events
(
    id              TEXT PRIMARY KEY,                                    -- event_id is a string
    meetup_id       TEXT         NOT NULL,                               -- meetup_id is a string and cannot be null
    source          VARCHAR(255) NOT NULL,                               -- Representing source as a string with max length 255
    source_url      TEXT         NOT NULL,                               -- Representing URL as TEXT
    title           TEXT         NOT NULL,
    start_date_time TIMESTAMPTZ  NOT NULL,                               -- OffsetDateTime as TIMESTAMPTZ in PostgreSQL
    description     TEXT,                                                -- Optional field
    event_url       TEXT,                                                -- Optional field representing URL as TEXT
    end_date_time   TIMESTAMPTZ,                                         -- Optional field
    has_start_time  BOOLEAN DEFAULT TRUE,                                -- Default value is true
    has_end_time    BOOLEAN DEFAULT TRUE,                                -- Default value is true
    CONSTRAINT unique_event_meetup UNIQUE (id, meetup_id),         -- Uniqueness constraint for event_id and meetup_id
    CONSTRAINT fk_meetup FOREIGN KEY (meetup_id) REFERENCES meetups (id) -- Foreign key referencing the meetups table
);

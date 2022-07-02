CREATE TABLE storage (
    directory        VARCHAR(255) NOT NULL CHECK (directory LIKE '/%' AND directory LIKE '%/'),
    filename         VARCHAR(255) NOT NULL,
    content_length   NUMERIC(19, 0) NOT NULL,
    last_modified    TIMESTAMP NOT NULL,
    file_contents    BLOB NOT NULL,
    compressed       NUMERIC(1, 0) NOT NULL CHECK (compressed IN (0, 1)),
    creation_time    TIMESTAMP DEFAULT current_timestamp NOT NULL,
    PRIMARY KEY (directory, filename)
);

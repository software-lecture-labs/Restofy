-- MENU rate limiting: one counter row per (scope, subject, fixed window). The row is the single shared truth every Menu
-- instance counts against, so a limit holds however many instances run. A request is counted with one atomic
-- INSERT ... ON CONFLICT DO UPDATE, so concurrent requests never read the same count.
--
-- scope is a fixed name chosen by the service (public, admin, internal budgets). subject is derived by the service from a
-- verified restaurant or actor id, or from the TCP peer of the connection; never from a header, parameter or body, so a
-- client cannot create rows of its own choosing. There is no foreign key and no personal data: a restaurant id and a user
-- id are opaque uuids, a peer is a network address.
--
-- window_start is the start of a fixed window aligned to the database clock (so every instance agrees on the window).
-- Finished windows are useless; the service deletes those older than two hours, and the index serves that delete.

CREATE TABLE api_rate_limit_windows (
    scope         varchar(40)  NOT NULL,
    subject       varchar(120) NOT NULL,
    window_start  timestamptz  NOT NULL,
    request_count bigint       NOT NULL,

    CONSTRAINT pk_api_rate_limit_windows PRIMARY KEY (scope, subject, window_start),
    CONSTRAINT ck_api_rate_limit_windows_count CHECK (request_count > 0)
);

CREATE INDEX ix_api_rate_limit_windows_window_start ON api_rate_limit_windows (window_start);

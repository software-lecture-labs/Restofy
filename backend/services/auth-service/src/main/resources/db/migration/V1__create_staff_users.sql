CREATE TABLE staff_users (
    id uuid PRIMARY KEY,
    email_normalized varchar(254) NOT NULL,
    password_hash varchar(60) NOT NULL,
    role varchar(20) NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_staff_users_email UNIQUE (email_normalized),
    CONSTRAINT ck_staff_users_email CHECK (
        email_normalized = lower(btrim(email_normalized))
        AND email_normalized ~ '^[a-z0-9.!#$%&''*+/=?^_`{|}~-]+@[a-z0-9.-]+\.[a-z]{2,}$'
    ),
    CONSTRAINT ck_staff_users_password_hash CHECK (
        password_hash ~ '^\$2[aby]\$12\$[./A-Za-z0-9]{53}$'
    ),
    CONSTRAINT ck_staff_users_role CHECK (role IN ('ADMIN', 'WAITER', 'KITCHEN')),
    CONSTRAINT ck_staff_users_version CHECK (version >= 0),
    CONSTRAINT ck_staff_users_dates CHECK (updated_at >= created_at)
);

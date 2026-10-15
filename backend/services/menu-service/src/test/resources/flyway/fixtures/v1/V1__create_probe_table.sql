CREATE TABLE public.migration_probe (
    id BIGINT PRIMARY KEY,
    note VARCHAR(100) NOT NULL
);

INSERT INTO public.migration_probe (id, note) VALUES (1, 'first migration');

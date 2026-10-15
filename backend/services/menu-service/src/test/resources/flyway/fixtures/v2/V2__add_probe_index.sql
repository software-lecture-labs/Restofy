CREATE INDEX migration_probe_note_idx ON public.migration_probe (note);

INSERT INTO public.migration_probe (id, note) VALUES (2, 'second migration');

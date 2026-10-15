-- Test-only script: the first statement is valid, the second is not.
-- A correct migration runner must roll back the first one too.
CREATE TABLE half_done (id INTEGER PRIMARY KEY) STRICT;
THIS IS NOT SQL;

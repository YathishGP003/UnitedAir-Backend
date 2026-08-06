-- ===========================================================================
--  V2.1 - Demo accounts
--
--  One account per user class in SRS 2.3. All three share the password
--  Demo!2026; the hashes below are distinct BCrypt(10) digests of that value,
--  each verified against BCryptPasswordEncoder before being committed here.
--
--  These are local demonstration credentials for a simulator-backed capstone
--  build. A real deployment provisions users through the identity provider and
--  should delete these rows.
-- ===========================================================================

INSERT INTO app_user (email, password_hash, display_name, role, active) VALUES
    ('passenger@unitedair.demo',
     '$2a$10$RsRLRdvL/4LMDQNvoOUjSua0LuaGEpxH9lTyvBmKpu3w4t1u9UIDO',
     'Ananya Rao',
     'PASSENGER',
     TRUE),
    ('staff@unitedair.demo',
     '$2a$10$9za9j3z6hQRDzqf3B7rHf.k3y9vt3Fg0c3lXsWW9zRl27X9bSufji',
     'Vikram Menon',
     'AIRLINE_STAFF',
     TRUE),
    ('admin@unitedair.demo',
     '$2a$10$U8NlTE7BJ/weq93j9fO9xuVEtD4aThK/kJnJC707NtPFDQVGgzuym',
     'Priya Krishnan',
     'ADMIN',
     TRUE)
ON DUPLICATE KEY UPDATE
    password_hash = VALUES(password_hash),
    display_name  = VALUES(display_name),
    role          = VALUES(role),
    active        = VALUES(active);

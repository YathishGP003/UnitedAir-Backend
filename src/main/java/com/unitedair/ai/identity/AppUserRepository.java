package com.unitedair.ai.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AppUserRepository {

    private final JdbcClient jdbc;

    public AppUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByEmail(String email) {
        return jdbc.sql("""
                    SELECT id, email, password_hash, display_name, role, active, created_at, last_login_at
                    FROM app_user
                    WHERE email = :email
                """)
                .param("email", email)
                .query(AppUserRepository::map)
                .optional();
    }

    public Optional<AppUser> findById(Long id) {
        return jdbc.sql("""
                    SELECT id, email, password_hash, display_name, role, active, created_at, last_login_at
                    FROM app_user
                    WHERE id = :id
                """)
                .param("id", id)
                .query(AppUserRepository::map)
                .optional();
    }

    public void touchLastLogin(Long id) {
        jdbc.sql("UPDATE app_user SET last_login_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", id)
                .update();
    }

    @Transactional
    public AppUser createPassenger(
            String email,
            String passwordHash,
            String displayName,
            String phone) {
        jdbc.sql("""
                    INSERT INTO app_user
                        (email, password_hash, display_name, role, active)
                    VALUES (:email, :passwordHash, :displayName, 'PASSENGER', TRUE)
                """)
                .param("email", email)
                .param("passwordHash", passwordHash)
                .param("displayName", displayName)
                .update();

        AppUser created = findByEmail(email).orElseThrow();
        jdbc.sql("""
                    INSERT INTO passenger_profile (user_id, phone)
                    VALUES (:userId, :phone)
                """)
                .param("userId", created.id())
                .param("phone", phone)
                .update();
        return created;
    }

    private static AppUser map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp lastLogin = rs.getTimestamp("last_login_at");
        return new AppUser(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                Role.fromString(rs.getString("role")),
                rs.getBoolean("active"),
                toInstant(rs.getTimestamp("created_at")),
                lastLogin == null ? null : lastLogin.toInstant());
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}

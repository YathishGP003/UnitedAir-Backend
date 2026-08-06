package com.unitedair.ai.conversation;

import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import java.util.Optional;

import com.unitedair.ai.privacy.PiiRedactor;
import com.unitedair.ai.shared.UnitedAirProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class ChatMemoryStoreTest {

    @Test
    void activeWindowRetainsExactlyTenStoredMessages() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Integer> query =
                mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Integer.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.empty());
        UnitedAirProperties properties = new UnitedAirProperties();
        properties.getChatMemory().setMaxTurns(10);

        new ChatMemoryStore(jdbc, new PiiRedactor(), properties)
                .applyWindow("session-1");

        verify(statement).param("keep", 10);
    }

    @Test
    void logoutClearIsScopedToTheOwningUser() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        ChatMemoryStore store = new ChatMemoryStore(
                jdbc, new PiiRedactor(), new UnitedAirProperties());

        store.clearActiveMemoryForUser(42L);

        verify(jdbc.sql(org.mockito.ArgumentMatchers.contains(
                        "JOIN chat_session s ON s.session_uuid = m.session_uuid"))
                .param("userId", 42L))
                .update();
    }
}

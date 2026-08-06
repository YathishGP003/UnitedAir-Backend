package com.unitedair.ai.tools;

import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class SimulatorScheduleRefresherTest {

    @Test
    void rebasesDemoIrregularOperationsAgainstTheCurrentDate() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        SimulatorScheduleRefresher refresher = new SimulatorScheduleRefresher(jdbc);

        refresher.rebase();

        verify(jdbc).sql(contains("DATE_ADD(CURDATE(), INTERVAL 1 DAY)"));
        verify(jdbc).sql(contains("DATE_ADD(CURDATE(), INTERVAL 2 DAY)"));
    }
}

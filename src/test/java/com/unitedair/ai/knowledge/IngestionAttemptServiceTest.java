package com.unitedair.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class IngestionAttemptServiceTest {

    @Test
    void successfulAttemptUpdateCommitsWithTheDocumentRowsItReferences()
            throws NoSuchMethodException {
        Method method = IngestionAttemptService.class.getMethod(
                "succeed",
                String.class,
                Long.class,
                Long.class,
                String.class,
                int.class,
                long.class);

        Transactional transaction = method.getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.propagation()).isEqualTo(Propagation.REQUIRED);
    }
}

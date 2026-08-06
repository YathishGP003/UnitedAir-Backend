package com.unitedair.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KbSeederTest {

    @Test
    void convertsTwoDigitFilenameNumberToThreeDigitCatalogCode() {
        assertThat(KbSeeder.documentCodeForFilename(
                "UnitedAir_AI_KB_03_Baggage_Policy_And_Handling(1).txt"))
                .isEqualTo("KB-AIR-003");
        assertThat(KbSeeder.documentCodeForFilename(
                "KB_10_Cross_Border_Passenger_Protection.txt"))
                .isEqualTo("KB-AIR-010");
    }

    @Test
    void leavesAdHocFilesWithoutAClaimedDocumentCode() {
        assertThat(KbSeeder.documentCodeForFilename("notes.txt")).isNull();
    }
}

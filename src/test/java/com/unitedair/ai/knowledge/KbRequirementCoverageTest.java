package com.unitedair.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class KbRequirementCoverageTest {

    private static final Path KB = Path.of("..", "kb");
    private static final List<String> PROVENANCE_FIELDS = List.of(
            "source_authority",
            "source_title",
            "source_url_or_internal_reference",
            "source_publication_date",
            "verified_on",
            "approved_by");

    @Test
    void baggageMealAndRegulatoryDocumentsCoverEveryNamedSrsTerm() throws IOException {
        String content = String.join("\n",
                read("UnitedAir_AI_KB_03_Baggage_Policy_And_Handling(1).txt"),
                read("KB_06_Special_Services_Assistance_And_FFP(1).txt"),
                read("KB_09_Mishandled_Baggage_WorldTracer_PIR_And_Liability.txt"),
                read("KB_10_Cross_Border_Passenger_Protection.txt"));

        for (String term : List.of(
                "WorldTracer",
                "Property Irregularity Report",
                "PIR",
                "Montreal Convention",
                "1,519 SDR",
                "VGML",
                "excess baggage fee",
                "cross-border")) {
            assertThat(content).containsIgnoringCase(term);
        }
    }

    @Test
    void fr026AndFr028SourcesHaveCompleteProvenance() throws IOException {
        for (String file : List.of(
                "KB_09_Mishandled_Baggage_WorldTracer_PIR_And_Liability.txt",
                "KB_10_Cross_Border_Passenger_Protection.txt")) {
            String content = read(file);
            for (String field : PROVENANCE_FIELDS) {
                Matcher matcher = Pattern.compile(
                                "(?im)^" + Pattern.quote(field) + ":\\s*(\\S.+)$")
                        .matcher(content);
                assertThat(matcher.find())
                        .as("%s must populate %s", file, field)
                        .isTrue();
                assertThat(matcher.group(1).trim())
                        .as("%s.%s cannot be blank", file, field)
                        .isNotBlank()
                        .doesNotContain("TBD", "TODO");
            }
        }
    }

    @Test
    void disputeDocumentTitleCannotParseAsItsSeparatorLine() throws IOException {
        KbFrontMatter parsed = new KbFrontMatterParser().parse(
                read("UnitedAir_AI_KB_08_Dispute_Resolution_and_Service_Support.txt"),
                "fallback.txt");

        assertThat(parsed.title()).isEqualTo("Dispute Resolution and Service Support");
    }

    private static String read(String filename) throws IOException {
        return Files.readString(KB.resolve(filename));
    }
}

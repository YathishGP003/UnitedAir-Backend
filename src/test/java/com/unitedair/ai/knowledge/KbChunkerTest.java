package com.unitedair.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.unitedair.ai.shared.UnitedAirProperties;
import org.junit.jupiter.api.Test;

class KbChunkerTest {

    @Test
    void preservesSectionBoundariesAndTableRows() {
        UnitedAirProperties properties = new UnitedAirProperties();
        properties.getIngestion().setChunkMinChars(1);
        properties.getIngestion().setChunkTargetChars(90);
        properties.getIngestion().setChunkOverlapChars(10);
        KbChunker chunker = new KbChunker(properties);

        var chunks = chunker.chunk("""
                1 Baggage Allowance
                Economy | 15 kg | One bag
                Business | 30 kg | Two bags
                Additional information about checked baggage handling and restrictions.
                2 Cabin Baggage
                Economy | 7 kg | One item
                """);

        assertThat(chunks).extracting(KbChunker.ChunkDraft::section)
                .contains("1 Baggage Allowance", "2 Cabin Baggage");
        assertThat(chunks).anySatisfy(chunk ->
                assertThat(chunk.content()).contains("Economy | 15 kg | One bag"));
    }

    @Test
    void emptyParentHeadingDoesNotReplaceItsNumberedChild() {
        UnitedAirProperties properties = new UnitedAirProperties();
        properties.getIngestion().setChunkMinChars(20);
        properties.getIngestion().setChunkTargetChars(500);
        properties.getIngestion().setChunkOverlapChars(10);
        KbChunker chunker = new KbChunker(properties);

        var chunks = chunker.chunk("""
                5 Upgrades

                5.1 Upgrade Pathways for Passengers
                Bid upgrades and points upgrades are available subject to inventory.
                This sentence makes the child section large enough to stand alone.
                """);

        assertThat(chunks).extracting(KbChunker.ChunkDraft::section)
                .containsExactly("5.1 Upgrade Pathways for Passengers");
    }
}

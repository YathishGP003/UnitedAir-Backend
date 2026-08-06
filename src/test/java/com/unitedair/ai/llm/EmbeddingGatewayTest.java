package com.unitedair.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.unitedair.ai.shared.UnitedAirProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

class EmbeddingGatewayTest {

    @SuppressWarnings("unchecked")
    @Test
    void offlineQueryDoesNotAssumeExistingCorpusUsesTheLocalEmbeddingSpace() {
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        EmbeddingGateway gateway = new EmbeddingGateway(
                AiMode.OFFLINE, provider, new UnitedAirProperties(), new HostedCallLimiter(2));

        assertThat(gateway.embedQuery("baggage allowance").comparable()).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    void deterministicCorpusUsesAComparableDeterministicQueryEvenWhenChatModeIsLive() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);

        EmbeddingGateway gateway = new EmbeddingGateway(
                AiMode.LIVE, provider, new UnitedAirProperties(), new HostedCallLimiter(2));

        EmbeddingGateway.QueryEmbedding query =
                gateway.embedQuery("baggage allowance", "DETERMINISTIC");

        assertThat(query.comparable()).isTrue();
        assertThat(query.vector()).hasSize(1536);
        org.mockito.Mockito.verifyNoInteractions(model);
    }

    @SuppressWarnings("unchecked")
    @Test
    void hostedCorpusDoesNotCompareAgainstAnOfflineDeterministicQuery() {
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        EmbeddingGateway gateway = new EmbeddingGateway(
                AiMode.OFFLINE, provider, new UnitedAirProperties(), new HostedCallLimiter(2));

        assertThat(gateway.embedQuery("baggage allowance", "HOSTED").comparable()).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    void repeatedNormalisedQueryUsesOneHostedEmbeddingCall() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        when(provider.getObject()).thenReturn(model);
        when(model.embed("  baggage allowance ")).thenReturn(new float[] {1, 0, 0});

        EmbeddingGateway gateway = new EmbeddingGateway(
                AiMode.LIVE, provider, new UnitedAirProperties(), new HostedCallLimiter(2));

        var first = gateway.embedQuery("  baggage allowance ");
        var second = gateway.embedQuery("baggage allowance");

        assertThat(second.vector()).containsExactly(first.vector());
        verify(model).embed("  baggage allowance ");
    }

    @SuppressWarnings("unchecked")
    @Test
    void focusedQueriesUseOneHostedBatchAndReuseTheCache() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        when(model.embed(List.of("gate change", "upgrade pool")))
                .thenReturn(List.of(
                        new float[] {1, 0, 0},
                        new float[] {0, 1, 0}));

        EmbeddingGateway gateway = new EmbeddingGateway(
                AiMode.LIVE, provider, new UnitedAirProperties(),
                new HostedCallLimiter(2));

        var first = gateway.embedQueries(
                List.of("gate change", "upgrade pool"), "HOSTED");
        var second = gateway.embedQueries(
                List.of("gate change", "upgrade pool"), "HOSTED");

        assertThat(first).hasSize(2)
                .allSatisfy(query -> assertThat(query.comparable()).isTrue());
        assertThat(second.get(0).vector()).containsExactly(first.get(0).vector());
        assertThat(second.get(1).vector()).containsExactly(first.get(1).vector());
        verify(model).embed(List.of("gate change", "upgrade pool"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void documentEmbeddingReportsItsComparableSpaceAndDimensions() {
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        EmbeddingGateway gateway = new EmbeddingGateway(
                AiMode.OFFLINE, provider, new UnitedAirProperties(), new HostedCallLimiter(2));

        EmbeddingGateway.EmbeddingBatch batch =
                gateway.embedWithProvenance(java.util.List.of("one", "two"));

        assertThat(batch.generationSource()).isEqualTo("DETERMINISTIC");
        assertThat(batch.modelIdentifier()).isEqualTo("deterministic-hashed-bow-v1");
        assertThat(batch.dimensions()).isEqualTo(1536);
        assertThat(batch.vectors()).hasSize(2)
                .allSatisfy(vector -> assertThat(vector).hasSize(1536));
    }
}

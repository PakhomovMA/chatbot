package com.personal.chatbot.service.embedding;

import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.common.ai.model.ModelType;
import com.embabel.common.ai.model.PricingModel;
import com.personal.chatbot.utils.EmbeddingAudit;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Exposes a {@link KnowledgeEmbeddingService} as Embabel's {@link EmbeddingService} so it can be
 * handed to {@code LuceneSearchOperations} (Phase 3). Embabel decorates this bean with its own
 * tracking wrapper at startup; inject it by the {@link EmbeddingService} type only.
 */
public final class EmbabelEmbeddingServiceAdapter implements EmbeddingService {

    private final KnowledgeEmbeddingService delegate;

    public EmbabelEmbeddingServiceAdapter(KnowledgeEmbeddingService delegate) {
        this.delegate = delegate;
    }

    @Override
    public float[] embed(String text) {
        return delegate.embed(text);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        try {
            List<float[]> vectors = delegate.embed(texts);
            EmbeddingAudit.vectorsProduced(vectors.size());
            return vectors;
        } catch (RuntimeException e) {
            EmbeddingAudit.failed(e);
            throw e;
        }
    }

    @Override
    public int getDimensions() {
        return delegate.dimensions();
    }

    @Override
    public String getName() {
        return delegate.modelName();
    }

    @Override
    public String getProvider() {
        return delegate.provider();
    }

    @Override
    public ModelType getType() {
        return ModelType.EMBEDDING;
    }

    /** Local inference: no cost. */
    @Override
    public @Nullable PricingModel getPricingModel() {
        return null;
    }
}

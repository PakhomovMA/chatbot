package com.personal.chatbot.models.embedding;

/**
 * Identity of the vector space an index was built in (docs/system-plan.md INV-05). Two services
 * with equal fingerprints produce comparable vectors; anything else must not share an index.
 *
 * @param provider      backend family, e.g. {@code onnx} or {@code ollama}
 * @param model         logical model name
 * @param artifactHash  short digest of the actual weights (file digest or registry digest)
 * @param dimensions    vector length
 * @param prefixVersion version of the instruction prefixes applied to texts
 * @param normalized    whether vectors are L2-normalised
 */
public record EmbeddingFingerprint(
        String provider,
        String model,
        String artifactHash,
        int dimensions,
        String prefixVersion,
        boolean normalized
) {

    /** Canonical single-line form stored in the index manifest and reported by health. */
    public String value() {
        return String.join("/", provider, model, artifactHash, Integer.toString(dimensions),
                prefixVersion, normalized ? "l2" : "raw");
    }
}

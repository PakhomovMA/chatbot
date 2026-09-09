package com.personal.chatbot.models.agent;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the list of cited passage numbers from whatever shape the model wrote it in.
 *
 * <p>The schema asks for {@code [1, 2]} and models mostly write that, but not always: gemma4 nests
 * each number in an array of its own ({@code [[1], [2]]}), and quoted numbers ({@code ["1"]}) are a
 * common variant too. Jackson rejects those, the whole answer is discarded, and the question is asked
 * again from scratch — an expensive way to lose an answer that was otherwise correct. Every integer
 * found anywhere in the value is collected instead, in the order written; anything else is skipped.
 * Nothing is trusted by this: {@link com.personal.chatbot.service.chat.GroundingVerifier} still drops
 * every number that does not point at a passage the model was actually shown.
 */
public class CitationNumbers extends ValueDeserializer<List<Integer>> {

    @Override
    public List<Integer> deserialize(JsonParser parser, DeserializationContext context) {
        List<Integer> numbers = new ArrayList<>();
        collect(context.readTree(parser), numbers);
        return List.copyOf(numbers);
    }

    @Override
    public List<Integer> getNullValue(DeserializationContext context) {
        return List.of();
    }

    private void collect(JsonNode node, List<Integer> into) {
        if (node == null) {
            return;
        }
        if (node.isArray() || node.isObject()) {
            node.values().forEach(child -> collect(child, into));
        } else if (node.isIntegralNumber()) {
            into.add(node.asInt());
        } else if (node.isString()) {
            try {
                into.add(Integer.parseInt(node.asString().strip()));
            } catch (NumberFormatException ignored) {
                // A label rather than a passage number; the verifier would drop it anyway.
            }
        }
    }
}

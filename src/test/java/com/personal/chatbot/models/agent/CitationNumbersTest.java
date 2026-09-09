package com.personal.chatbot.models.agent;

import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The citation list is read from every shape a model writes it in (docs/eval-log.md). */
class CitationNumbersTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static List<Integer> cited(String citedEvidenceJson) {
        return MAPPER.readValue("{\"answer\":\"a\",\"citedEvidence\":" + citedEvidenceJson
                + ",\"evidenceSufficient\":true}", GroundedAnswerDraft.class).citedEvidence();
    }

    @Test
    void theShapeTheSchemaAsksForIsReadUnchanged() {
        assertThat(cited("[1, 2]")).containsExactly(1, 2);
        assertThat(cited("[]")).isEmpty();
        assertThat(cited("null")).isEmpty();
    }

    @Test
    void aNumberNestedOrQuotedIsStillANumber() {
        assertThat(cited("[[1], [2]]")).containsExactly(1, 2);   // gemma4:12b writes this
        assertThat(cited("[\"1\", \"2\"]")).containsExactly(1, 2);
        assertThat(cited("[[1, 3], 2]")).containsExactly(1, 3, 2);
        assertThat(cited("3")).containsExactly(3);
    }

    @Test
    void whatIsNotANumberIsSkippedRatherThanFailingTheWholeAnswer() {
        assertThat(cited("[1, \"passage two\", null, 3]")).containsExactly(1, 3);
        assertThat(cited("\"none\"")).isEmpty();
    }

    /**
     * The leniency is in the reading, not in what the model is asked for: the schema Embabel puts in
     * the prompt must still describe an array of integers, or models would start writing something else.
     */
    @Test
    void theSchemaShownToTheModelStillAsksForAnArrayOfIntegers() {
        String schema = new SchemaGenerator(new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .with(new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED, JacksonOption.RESPECT_JSONPROPERTY_ORDER))
                .with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT)
                .build()).generateSchema(GroundedAnswerDraft.class).toString();
        assertThat(schema).contains("\"citedEvidence\"").contains("\"type\":\"array\"").contains("\"type\":\"integer\"");
    }
}

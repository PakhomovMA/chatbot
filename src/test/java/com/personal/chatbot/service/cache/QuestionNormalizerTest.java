package com.personal.chatbot.service.cache;

import org.junit.jupiter.api.Test;

import static com.personal.chatbot.service.cache.QuestionNormalizer.normalize;
import static org.assertj.core.api.Assertions.assertThat;

/** K01: {@code norm-v1} folds what is not a different question and keeps everything that might be. */
class QuestionNormalizerTest {

    @Test
    void compatibilityFormsCaseAndSpacingDoNotMakeADifferentQuestion() {
        assertThat(normalize("  how do I   RESTART the\tpayments\nservice ?"))
                .isEqualTo(normalize("How do I restart the payments service?"))
                .isEqualTo("how do i restart the payments service");
        // NFKC: full-width letters, digits and signs, ligatures and the no-break space become plain ones.
        assertThat(normalize("ＳＥＶ－１ escalation")).isEqualTo("sev-1 escalation");
        assertThat(normalize("ﬁle upload　limit")).isEqualTo("file upload limit");
        assertThat(normalize("Как ПЕРЕЗАПУСТИТЬ сервис?")).isEqualTo("как перезапустить сервис");
    }

    @Test
    void onlyThePunctuationThatClosesTheSentenceGoes() {
        assertThat(normalize("restart?")).isEqualTo("restart");
        assertThat(normalize("restart?!")).isEqualTo("restart");
        assertThat(normalize("restart...")).isEqualTo("restart");
        assertThat(normalize("restart …")).isEqualTo("restart");
        assertThat(normalize("restart？")).isEqualTo("restart");
        assertThat(normalize("What changed in version 2.0.")).isEqualTo("what changed in version 2.0");
    }

    @Test
    void innerPunctuationAndIdentifiersStayAsTheyWere() {
        assertThat(normalize("What is the SEV-1 response time?")).isEqualTo("what is the sev-1 response time");
        assertThat(normalize("GLM-5.3: context length?")).isEqualTo("glm-5.3: context length");
        assertThat(normalize("Is v1.2 the same as v1.2.1?")).isEqualTo("is v1.2 the same as v1.2.1");
        assertThat(normalize("a.b?c")).isEqualTo("a.b?c");
    }

    @Test
    void whatChangesTheMeaningIsNotItsBusiness() {
        assertThat(normalize("Can I work from another country?"))
                .isNotEqualTo(normalize("Can I work from another country without approval?"));
        assertThat(normalize("SEV-1")).isNotEqualTo(normalize("SEV-2"));
        assertThat(normalize("restart the service")).isNotEqualTo(normalize("the service restart"));
    }
}

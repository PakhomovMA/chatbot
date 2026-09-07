package com.personal.chatbot.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextsTest {

    @Test
    void singleLineCollapsesWhitespaceAndTruncates() {
        assertThat(Texts.singleLine("  a\n\tb   c  ", 50)).isEqualTo("a b c");
        assertThat(Texts.singleLine("abcdef", 3)).isEqualTo("abc" + Texts.ELLIPSIS);
        assertThat(Texts.singleLine("abc", 3)).isEqualTo("abc");
    }

    @Test
    void truncateKeepsLineStructureAndTrimsBeforeTheEllipsis() {
        assertThat(Texts.truncate("first\nsecond", 50)).isEqualTo("first\nsecond");
        assertThat(Texts.truncate("word   tail", 6)).isEqualTo("word" + Texts.ELLIPSIS);
        assertThat(Texts.truncate("  padded  ", 20)).isEqualTo("padded");
    }
}

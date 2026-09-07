package com.personal.chatbot.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FilenamesTest {

    @Test
    void stripsDirectoriesAndUnsafeCharacters() {
        assertThat(Filenames.sanitize("../../etc/passwd")).isEqualTo("passwd");
        assertThat(Filenames.sanitize("C:\\Users\\me\\Report:final?.PDF")).isEqualTo("Report_final_.PDF");
        assertThat(Filenames.sanitize("  notes\u0000.md ")).isEqualTo("notes_.md");
        assertThat(Filenames.sanitize(".hidden")).isEqualTo("_hidden");
        assertThat(Filenames.sanitize("/")).isEmpty();
    }

    @Test
    void boundsLength() {
        String longName = "a".repeat(500) + ".md";
        assertThat(Filenames.sanitize(longName)).hasSize(Filenames.MAX_LENGTH);
    }

    @Test
    void extensionAndBaseName() {
        assertThat(Filenames.extension("Guide.MD")).isEqualTo("md");
        assertThat(Filenames.extension("archive.tar.gz")).isEqualTo("gz");
        assertThat(Filenames.extension("noext")).isEmpty();
        assertThat(Filenames.extension("trailing.")).isEmpty();
        assertThat(Filenames.baseName("Guide.md")).isEqualTo("Guide");
        assertThat(Filenames.baseName("noext")).isEqualTo("noext");
    }
}

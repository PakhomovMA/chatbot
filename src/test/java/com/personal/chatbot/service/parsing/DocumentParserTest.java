package com.personal.chatbot.service.parsing;

import com.embabel.agent.rag.model.LeafSection;
import com.embabel.agent.rag.model.NavigableDocument;
import com.personal.chatbot.exceptions.DocumentParseException;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.support.TestDocuments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentParserTest {

    @TempDir
    Path dir;

    private final DocumentParser parser = new DocumentParser();

    private static Document document(String filename, String mediaType, String title) {
        return Document.uploaded(title, filename, mediaType, 1, "hash-" + filename, Instant.now());
    }

    private Path write(String name, byte[] bytes) throws Exception {
        Path file = dir.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    private static List<String> leafTitles(NavigableDocument parsed) {
        List<String> titles = new ArrayList<>();
        parsed.leaves().forEach(leaf -> titles.add(leaf.getTitle()));
        return titles;
    }

    @Test
    void markdownHeadingsBecomeSections() throws Exception {
        Document doc = document("runbook.md", "text/markdown", "Payments Runbook");
        NavigableDocument parsed = parser.parse(doc, write("runbook.md", TestDocuments.markdown()));

        assertThat(parsed.getUri()).isEqualTo("kb://documents/" + doc.id());
        assertThat(parsed.getTitle()).isEqualTo("Payments Runbook");
        assertThat(parsed.getMetadata()).containsEntry(ProvenanceChunkTransformer.DOCUMENT_ID, doc.id())
                .containsEntry(ProvenanceChunkTransformer.DOCUMENT_VERSION, "1")
                .containsEntry(ProvenanceChunkTransformer.MEDIA_TYPE, "text/markdown");
        assertThat(leafTitles(parsed)).contains("Restart", "Database rollback");
        LeafSection restart = null;
        for (LeafSection leaf : parsed.leaves()) {
            if (leaf.getTitle().equals("Restart")) {
                restart = leaf;
            }
        }
        assertThat(restart).isNotNull();
        assertThat(restart.getText()).contains("systemctl restart payments");
    }

    @Test
    void pdfAndDocxAreExtractedToText() throws Exception {
        Document pdfDoc = document("guide.pdf", "application/pdf", "Guide");
        NavigableDocument pdf = parser.parse(pdfDoc, write("guide.pdf",
                TestDocuments.pdf(List.of("Deployment guide", "Deploy with the deploy script.", "Then verify the health endpoint."))));
        assertThat(String.join(" ", allText(pdf))).contains("deploy script").contains("health endpoint");

        Document docxDoc = document("policy.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Policy");
        NavigableDocument docx = parser.parse(docxDoc, write("policy.docx",
                TestDocuments.docx(List.of("Access policy", "Requests must be approved by two reviewers."))));
        assertThat(String.join(" ", allText(docx))).contains("two reviewers");
    }

    @Test
    void emptyOrUnparseableContentFails() throws Exception {
        Document empty = document("empty.md", "text/markdown", "Empty");
        assertThatThrownBy(() -> parser.parse(empty, write("empty.md", "   \n".getBytes())))
                .isInstanceOf(DocumentParseException.class);

        Document garbage = document("broken.pdf", "application/pdf", "Broken");
        assertThatThrownBy(() -> parser.parse(garbage, write("broken.pdf", "%PDF-1.7 this is not really a pdf".getBytes())))
                .isInstanceOf(DocumentParseException.class);

        assertThatThrownBy(() -> parser.parse(empty, dir.resolve("missing.md"))).isInstanceOf(DocumentParseException.class);
    }

    private static List<String> allText(NavigableDocument parsed) {
        List<String> texts = new ArrayList<>();
        parsed.leaves().forEach(leaf -> texts.add(leaf.getText()));
        return texts;
    }
}

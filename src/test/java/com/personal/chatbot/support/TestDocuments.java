package com.personal.chatbot.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Fixture generators: real PDF/DOCX bytes produced with the libraries Tika itself uses. */
public final class TestDocuments {

    public static final String MARKDOWN = """
            # Payments Runbook

            Intro paragraph about the payments service.

            ## Restart

            To restart the payment service run `systemctl restart payments` on the host.
            Check the status afterwards with `systemctl status payments`.

            ## Rollback

            Roll back by deploying the previous image tag with the deploy script.

            ### Database rollback

            Restore the latest snapshot before rolling back the application. Verify the snapshot checksum first.
            Stop the writers, then restore the snapshot into a fresh schema and run the migration checks.
            Compare row counts between the snapshot and the live schema before switching traffic over.
            Keep the previous schema for twenty-four hours in case the rollback itself has to be rolled back.
            Record the rollback in the incident log with the snapshot identifier and the operator name.
            Notify the payments on-call channel once the application is serving traffic from the restored schema.
            """;

    private TestDocuments() {
    }

    public static byte[] markdown() {
        return MARKDOWN.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] pdf(List<String> lines) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.setLeading(16f);
                content.newLineAtOffset(50, 700);
                for (String line : lines) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] docx(List<String> paragraphs) {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String paragraph : paragraphs) {
                document.createParagraph().createRun().setText(paragraph);
            }
            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

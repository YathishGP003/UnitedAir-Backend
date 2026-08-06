package com.unitedair.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class TextExtractorTest {

    private final TextExtractor extractor = new TextExtractor();

    @Test
    void extractsOrdinaryDocxWithoutTheFullOoxmlSchemaBundle() throws Exception {
        byte[] document;
        try (XWPFDocument docx = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            docx.createParagraph().createRun().setText("UnitedAir cabin baggage policy");
            docx.write(output);
            document = output.toByteArray();
        }

        assertThat(extractor.extract(document, "policy.docx"))
                .contains("UnitedAir cabin baggage policy");
    }

    @Test
    void extractsOrdinaryPdfWithoutEncryptionLibraries() throws Exception {
        byte[] document;
        try (PDDocument pdf = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("UnitedAir refund policy");
                content.endText();
            }
            pdf.save(output);
            document = output.toByteArray();
        }

        try (PDDocument ignored = Loader.loadPDF(document)) {
            assertThat(extractor.extract(document, "policy.pdf"))
                    .contains("UnitedAir refund policy");
        }
    }
}

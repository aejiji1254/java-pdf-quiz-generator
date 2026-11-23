package com.quiz;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PdfReaderTest {
    @Test public void readsSelectedRangeOf55PagePdfAndRejectsInvalidRanges() throws Exception {
        Path pdf = Path.of("samples", "b_behavior.pdf.pdf");
        PdfReader reader = new PdfReader();
        assertEquals(55, reader.pageCount(pdf));
        assertEquals(11, reader.readRange(pdf, 10, 20).size());
        for (int[] range : new int[][] {{0, 5}, {10, 9}, {50, 56}, {1, 51}}) {
            try {
                reader.readRange(pdf, range[0], range[1]);
                fail("범위를 거부해야 합니다.");
            } catch (PdfReader.InputLimitException expected) {
                assertFalse(expected.getMessage().isBlank());
            }
        }
    }

    @Test
    public void validatesCharacterCountAndKoreanRatioBoundaries() throws IOException {
        PdfReader.validateText(List.of("가".repeat(30) + "a".repeat(70) + " \n\t\u00a0"));
        PdfReader.validateText(List.of("ㄱ".repeat(100)));
        assertTextRejected("가".repeat(29) + "a".repeat(71), "한글 비율이 30% 미만");
        assertTextRejected("가".repeat(99), "최소 100자");
        assertTextRejected(" \n\t", "추출된 글자가 부족");
    }

    private void assertTextRejected(String text, String message) throws IOException {
        try {
            PdfReader.validateText(List.of(text));
            fail("입력 제한 예외가 필요합니다");
        } catch (PdfReader.InputLimitException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(message));
            if (text.strip().length() < 100) assertTrue(e.getMessage().contains("OCR"));
        }
    }

    @Test
    public void accepts50PagesAndRejects51BeforeTextValidation() throws IOException {
        Path pdf = temporary.getRoot().toPath().resolve("pages.pdf");
        Path oversizedPdf = temporary.getRoot().toPath().resolve("pages-51.pdf");
        try (PDDocument document = new PDDocument()) {
            addTextPage(document, "가".repeat(100), "한글 강의");
            for (int i = 1; i < 50; i++) document.addPage(new PDPage());
            document.save(pdf.toFile());
            assertEquals(50, new PdfReader().readPages(pdf.toString()).size());
            document.addPage(new PDPage());
            document.save(oversizedPdf.toFile());
        }
        try {
            new PdfReader().readPages(oversizedPdf.toString());
            fail("51페이지는 거부해야 합니다");
        } catch (PdfReader.InputLimitException e) {
            assertTrue(e.getMessage().contains("최대 50페이지"));
            assertTrue(e.getMessage().contains("51페이지"));
        }
    }

    @Test
    public void rejectsPdfWithNoExtractableText() throws IOException {
        Path pdf = temporary.getRoot().toPath().resolve("empty.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        try {
            new PdfReader().readPages(pdf.toString());
            fail("텍스트 부족 예외가 필요합니다");
        } catch (PdfReader.InputLimitException e) {
            assertTrue(e.getMessage().contains("추출된 글자가 부족"));
        }
    }
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void readsTextInPageOrderAndPreservesEmptyPages() throws IOException {
        Path pdf = temporary.getRoot().toPath().resolve("text.pdf");
        try (PDDocument document = new PDDocument()) {
            addTextPage(document, "가".repeat(60), ": First definition");
            document.addPage(new PDPage());
            addTextPage(document, "나".repeat(60), ": Last definition");
            document.save(pdf.toFile());
        }

        List<String> pages = new PdfReader().readPages(pdf.toString());

        assertEquals(3, pages.size());
        assertEquals("가".repeat(60) + "\n: First definition", normalize(pages.get(0)));
        assertTrue(pages.get(1).isBlank());
        assertEquals("나".repeat(60) + "\n: Last definition", normalize(pages.get(2)));
    }

    @Test
    public void rejectsMissingFile() throws IOException {
        try {
            new PdfReader().readPages(temporary.getRoot().toPath().resolve("missing.pdf").toString());
            fail("파일이 없으면 예외가 발생해야 합니다.");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("PDF 파일이 없습니다"));
            assertTrue(e.getMessage().contains("missing.pdf"));
        }
    }

    @Test
    public void rejectsInvalidPdf() throws IOException {
        Path invalid = temporary.newFile("invalid.pdf").toPath();
        Files.writeString(invalid, "This is not a PDF.");
        try {
            new PdfReader().readPages(invalid.toString());
            fail("PDF가 아니면 예외가 발생해야 합니다.");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("유효한 PDF인지"));
            assertTrue(e.getMessage().contains("invalid.pdf"));
            assertTrue(e.getCause() instanceof IOException);
        }
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").strip();
    }

    private static void addTextPage(PDDocument document, String term, String definition)
            throws IOException {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(PdfTestSupport.koreanFont(document), 12);
            content.newLineAtOffset(50, 700);
            content.showText(term);
            content.newLineAtOffset(0, -20);
            content.showText(definition);
            content.endText();
        }
    }
}

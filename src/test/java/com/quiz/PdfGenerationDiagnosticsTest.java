package com.quiz;

import static org.junit.Assert.*;

import com.quiz.gui.QuizWorkflow;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import java.awt.image.BufferedImage;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PdfGenerationDiagnosticsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void readablePdfWithoutTermDefinitionRelationExplainsZeroQuestions() throws Exception {
        Path pdf = temporary.getRoot().toPath().resolve("plain.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PdfTestSupport.koreanFont(document), 12);
                content.newLineAtOffset(50, 700);
                content.showText("설명 관계가 없는 문장 ".repeat(15));
                content.endText();
            }
            document.save(pdf.toFile());
        }
        QuizWorkflow.Preview preview = new QuizWorkflow().generate(pdf);
        assertTrue(preview.set().questions().isEmpty());
        assertTrue(preview.diagnostics().contains("PDF 읽기 성공"));
        assertTrue(preview.diagnostics().contains("용어 후보: 0개"));
        assertTrue(preview.diagnostics().contains("최종 출제: O/X 0개, 4지선다 0개"));
        assertTrue(preview.diagnostics().contains("적합한 용어·설명 관계를 찾지 못했습니다"));
    }

    @Test public void samplePdfStillProducesQuestionsAndStageCounts() throws Exception {
        QuizWorkflow.Preview preview = new QuizWorkflow().generate(Path.of("sample.pdf"));
        assertEquals(14, preview.set().questions().size());
        assertTrue(preview.diagnostics().contains("PDF 읽기 성공: 23페이지"));
        assertTrue(preview.diagnostics().contains("용어 후보: 19개, 유효한 용어·설명 쌍: 18개"));
        assertTrue(preview.diagnostics().contains("최종 출제:"));
    }

    @Test public void otherProjectPdfReportsSingleSafeOxAndReasonsForRejectedPairs() throws Exception {
        QuizWorkflow.Preview preview = new QuizWorkflow().generate(
                Path.of("samples", "c_behavior.pdf.pdf"));
        assertEquals(1, preview.set().questions().size());
        assertEquals(SavedQuestion.Type.OX, preview.set().questions().get(0).type());
        SavedQuestion only = preview.set().questions().get(0);
        assertEquals(0, only.correctIndex());
        assertTrue(only.question().startsWith("다음 설명은 '"));
        assertTrue(only.question().contains("'에 대한 설명이다.\n\n설명: "));
        assertTrue(preview.skipped().stream().anyMatch(s -> s.contains("대체 설명 후보가 없음")));
        assertTrue(preview.diagnostics().contains("홀수 문항 수로 완전한 동수 배정 불가"));
        assertTrue(preview.diagnostics().contains("PDF 읽기 성공: 32페이지"));
        assertTrue(preview.diagnostics().contains("출제 제외"));
        assertTrue(preview.diagnostics().contains("4지선다 적합 용어 후보: 2개, 최종 채택: 0개"));
        assertTrue(preview.diagnostics().contains("4지선다 제외 사유"));
    }

    @Test public void overLimitProjectPdfFailsAtReadingStage() throws Exception {
        try {
            new QuizWorkflow().generate(Path.of("samples", "b_behavior.pdf.pdf"));
            fail("55페이지 PDF는 현재 입력 제한을 넘어야 합니다.");
        } catch (PdfReader.InputLimitException expected) {
            assertTrue(expected.getMessage().contains("55"));
            assertTrue(expected.getMessage().contains("50"));
        }
    }

    @Test public void threeGoodTermsCanMakeOxButNotFourChoices() throws Exception {
        Path pdf = temporary.getRoot().toPath().resolve("three-terms.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PdfTestSupport.koreanFont(document), 12);
                content.newLineAtOffset(50, 700);
                content.showText("데이터베이스 수업에서 핵심 개념을 다루는 강의 자료입니다. ".repeat(2));
                content.newLineAtOffset(0, -20);
                for (String line : new String[] {
                        "캐시: 자주 사용하는 데이터를 빠르게 다시 제공하는 저장소",
                        "버퍼: 입출력 속도 차이를 완화하도록 데이터를 잠시 보관하는 공간",
                        "인덱스: 검색할 위치를 빠르게 찾도록 정렬된 참조 구조"}) {
                    content.showText(line);
                    content.newLineAtOffset(0, -20);
                }
                content.endText();
            }
            document.save(pdf.toFile());
        }
        QuizWorkflow.Preview preview = new QuizWorkflow().generate(pdf);
        assertEquals(3, preview.set().questions().size());
        assertTrue(preview.diagnostics().contains("O/X 적합 용어 후보: 3개, 최종 채택: 3개"));
        assertTrue(preview.diagnostics().contains("4지선다 적합 용어 후보: 3개, 최종 채택: 0개"));
        assertTrue(preview.diagnostics().contains("4개 미만"));
    }

    @Test public void imageOnlyPdfReportsMissingOcrSupport() throws Exception {
        Path pdf = temporary.getRoot().toPath().resolve("image-only.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, image), 50, 600);
            }
            document.save(pdf.toFile());
        }
        try {
            new QuizWorkflow().generate(pdf);
            fail("이미지만 있는 PDF는 텍스트 추출 실패를 보고해야 합니다.");
        } catch (PdfReader.InputLimitException expected) {
            assertTrue(expected.getMessage().contains("OCR"));
        }
    }
}

package com.quiz.gui;

import com.quiz.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class DirectQuestionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    QuizWorkflow workflow() {
        return new QuizWorkflow(new QuizRepository(temporary.getRoot().toPath().resolve("quizzes")),
                new QuizResultRepository(temporary.getRoot().toPath().resolve("results")),
                Clock.systemUTC(), QuizGenerator::new);
    }

    static QuizWorkflow.Preview empty(String source) {
        return new QuizWorkflow.Preview(new SavedQuizSet(UUID.randomUUID().toString(), source, List.of()),
                List.of("기존 제외 사유"), "기존 진단");
    }

    static Path plainPdf(Path file) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(PDType0Font.load(doc, new File(System.getProperty("test.koreanFont",
                        "C:/Windows/Fonts/malgun.ttf"))), 12);
                content.newLineAtOffset(50, 700);
                for (int i = 0; i < 10; i++) {
                    content.showText("용어와 설명의 연결 없이 일반 문장으로 작성된 한국어 강의 자료입니다.");
                    content.newLineAtOffset(0, -20);
                }
                content.endText();
            }
            doc.save(file.toFile());
        }
        return file;
    }

    static void invalid(Runnable action) {
        try {
            action.run();
            fail("잘못된 입력 거부");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isBlank());
        }
    }

    @Test public void addsBothTypesWithInheritedSourceFreshIdsAndImmutablePreview() {
        QuizWorkflow workflow = workflow();
        QuizWorkflow.Preview original = empty("lecture.pdf");
        QuizWorkflow.Preview ox = workflow.addQuestion(original, SavedQuestion.Type.OX,
                "직접 질문", List.of("O", "X"), 1, "해설");
        QuizWorkflow.Preview both = workflow.addQuestion(ox, SavedQuestion.Type.MULTIPLE_CHOICE,
                "객관식 질문", List.of("가", "나", "다", "라"), 3, "[직접 작성] 객관식 해설");
        assertTrue(original.set().questions().isEmpty());
        assertEquals(1, ox.set().questions().size());
        assertEquals(2, both.set().questions().size());
        assertEquals(original.set().id(), both.set().id());
        assertEquals(original.skipped(), both.skipped());
        assertEquals(original.diagnostics(), both.diagnostics());
        assertEquals(2, both.set().questions().stream().map(SavedQuestion::id).distinct().count());
        assertEquals("[직접 작성] 해설", both.set().questions().get(0).explanation());
        assertEquals("[직접 작성] 객관식 해설", both.set().questions().get(1).explanation());
        for (SavedQuestion q : both.set().questions()) {
            assertEquals(UUID.fromString(q.id()).toString(), q.id());
            assertEquals("lecture.pdf", q.sourcePdf());
            assertNull(q.page());
            assertNull(q.lineNumber());
        }
    }

    @Test public void rejectsInvalidFieldsBeforeAddingAndCannotUseMarkerAsExplanation() {
        QuizWorkflow w = workflow();
        QuizWorkflow.Preview p = empty("source.pdf");
        List<String> options = List.of("가", "나", "다", "라");
        invalid(() -> w.addQuestion(p, SavedQuestion.Type.MULTIPLE_CHOICE, " ", options, 0, "해설"));
        for (String text : List.of("", " ", "[직접 작성] "))
            invalid(() -> w.addQuestion(p, SavedQuestion.Type.MULTIPLE_CHOICE, "질문", options, 0, text));
        invalid(() -> w.addQuestion(p, SavedQuestion.Type.MULTIPLE_CHOICE, "질문", options, 0, null));
        invalid(() -> w.addQuestion(p, SavedQuestion.Type.MULTIPLE_CHOICE, "질문", List.of("가", " ", "다", "라"), 0, "해설"));
        invalid(() -> w.addQuestion(p, SavedQuestion.Type.MULTIPLE_CHOICE, "질문", List.of("ABC", " ａｂｃ ", "다", "라"), 0, "해설"));
        invalid(() -> w.addQuestion(p, SavedQuestion.Type.MULTIPLE_CHOICE, "질문", options, -1, "해설"));
        invalid(() -> w.addQuestion(p, SavedQuestion.Type.MULTIPLE_CHOICE, "질문", options, 4, "해설"));
        invalid(() -> w.addQuestion(p, SavedQuestion.Type.MULTIPLE_CHOICE, "질문", List.of("가", "나"), 0, "해설"));
        invalid(() -> w.addQuestion(p, SavedQuestion.Type.OX, "질문", List.of("X", "O"), 0, "해설"));
        invalid(() -> w.addQuestion(p, SavedQuestion.Type.OX, "질문", List.of("O", "X"), 2, "해설"));
        assertTrue(p.set().questions().isEmpty());
        assertFalse(Files.exists(temporary.getRoot().toPath().resolve("quizzes")));
    }

    @Test public void zeroQuestionTextPdfCanBeAddedSavedPlayedAndReviewed() throws Exception {
        QuizWorkflow w = workflow();
        Path pdf = plainPdf(temporary.getRoot().toPath().resolve("plain.pdf"));
        QuizWorkflow.Preview generated = w.generate(pdf);
        assertTrue(generated.set().questions().isEmpty());
        assertTrue(generated.diagnostics().contains("PDF 읽기 성공"));
        QuizWorkflow.Preview ox = w.addQuestion(generated, SavedQuestion.Type.OX, "추가 OX", List.of("O", "X"), 0, "해설");
        QuizWorkflow.Preview both = w.addQuestion(ox, SavedQuestion.Type.MULTIPLE_CHOICE, "추가 객관식",
                List.of("가", "나", "다", "라"), 2, "해설");
        SavedQuizSet saved = w.saveReviewed(both, Set.of());
        Path quizFile = temporary.getRoot().toPath().resolve("quizzes").resolve(saved.id() + ".json");
        SavedQuizSet loaded = w.loadQuiz(quizFile);
        assertEquals(saved, loaded);
        QuizSession session = w.start(loaded);
        assertFalse(session.submit("X").correct());
        assertTrue(session.submit("O").correct());
        session.next();
        assertTrue(session.submit("3").correct());
        session.next();
        QuizWorkflow.Review review = w.loadReview(quizFile, w.saveResult(session));
        assertEquals(List.of(loaded.questions().get(0)), review.wrong());
        QuizSession retry = w.startReview(review);
        assertTrue(retry.submit("O").correct());
        retry.next();
        assertTrue(w.loadReview(quizFile, w.saveResult(retry)).wrong().isEmpty());
    }

    @Test public void manualTermPreviewsInheritTheirExactSourceLabel() throws Exception {
        QuizWorkflow w = workflow();
        for (String associated : List.of("", "lecture.pdf")) {
            ManualTermSet manual = ManualTermSet.empty(associated).add("캐시", "자주 사용하는 데이터를 보관하는 공간");
            QuizWorkflow.Preview p = w.generateManual(manual);
            QuizWorkflow.Preview added = w.addQuestion(p, SavedQuestion.Type.OX, "직접 질문", List.of("O", "X"), 0, "해설");
            SavedQuestion q = added.set().questions().getLast();
            assertEquals(manual.sourceLabel(), q.sourcePdf());
            SavedQuizSet saved = w.saveReviewed(added, Set.of());
            assertEquals(saved, w.loadQuiz(temporary.getRoot().toPath().resolve("quizzes").resolve(saved.id() + ".json")));
        }
    }
}

package com.quiz.gui;

import static org.junit.Assert.*;

import com.quiz.QuizGenerator;
import com.quiz.QuizRepository;
import com.quiz.QuizResult;
import com.quiz.QuizResultRepository;
import com.quiz.SavedQuestion;
import com.quiz.SavedQuizSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class QuizWorkflowTest {
    @Test public void editsBothTypesThenSavesLoadsPlaysAndReviewsEditedQuestions() throws Exception {
        QuizWorkflow workflow = workflow();
        SavedQuizSet original = sampleSet();
        SavedQuestion ox = original.questions().get(0);
        SavedQuestion mc = original.questions().get(1);
        QuizWorkflow.Preview preview = new QuizWorkflow.Preview(original, List.of("제외 사유"), "진단");
        preview = workflow.editQuestion(preview, ox.id(), "수정한 O/X 질문", List.of("O", "X"),
                1, "수정한 O/X 해설");
        preview = workflow.editQuestion(preview, mc.id(), "수정한 객관식 질문",
                List.of("새 가", "새 나", "새 다", "새 라"), 3, "수정한 객관식 해설");
        assertEquals(List.of("제외 사유"), preview.skipped());
        assertEquals("진단", preview.diagnostics());
        assertEquals("O/X 질문", original.questions().get(0).question());
        SavedQuizSet saved = workflow.saveReviewed(preview, Set.of());
        Path quizFile = temporary.getRoot().toPath().resolve("quizzes").resolve(saved.id() + ".json");
        SavedQuizSet loaded = workflow.loadQuiz(quizFile);
        assertEquals(saved, loaded);
        assertEquals("수정한 O/X 질문", loaded.questions().get(0).question());
        assertEquals("수정한 O/X 해설", loaded.questions().get(0).explanation());
        assertEquals(List.of("새 가", "새 나", "새 다", "새 라"), loaded.questions().get(1).options());
        for (int i = 0; i < 2; i++) {
            assertEquals(original.questions().get(i).id(), loaded.questions().get(i).id());
            assertEquals(original.questions().get(i).sourcePdf(), loaded.questions().get(i).sourcePdf());
            assertEquals(original.questions().get(i).page(), loaded.questions().get(i).page());
            assertEquals(original.questions().get(i).lineNumber(), loaded.questions().get(i).lineNumber());
        }
        QuizSession session = workflow.start(loaded);
        assertFalse(session.submit("O").correct());
        assertTrue(session.submit("X").correct());
        session.next();
        assertFalse(session.submit("3").correct());
        assertTrue(session.submit("4").correct());
        session.next();
        QuizWorkflow.Review review = workflow.loadReview(quizFile, workflow.saveResult(session));
        assertEquals(List.of(loaded.questions().get(1), loaded.questions().get(0)), review.wrong());
        QuizSession retry = workflow.startReview(review);
        assertEquals("수정한 객관식 질문", retry.current().question());
        assertEquals("수정한 객관식 해설", retry.current().explanation());
        assertTrue(retry.submit("4").correct());
        retry.next();
        assertTrue(retry.submit("X").correct());
        retry.next();
        assertTrue(workflow.loadReview(quizFile, workflow.saveResult(retry)).wrong().isEmpty());
    }

    @Test public void invalidEditsAreRejectedWithoutChangingPreviewOrWritingFiles() {
        QuizWorkflow workflow = workflow();
        SavedQuizSet original = sampleSet();
        QuizWorkflow.Preview preview = new QuizWorkflow.Preview(original, List.of());
        String id = original.questions().get(1).id();
        assertInvalid(() -> workflow.editQuestion(preview, id, " ", List.of("가", "나", "다", "라"), 0, "해설"));
        assertInvalid(() -> workflow.editQuestion(preview, id, "질문", List.of("가", " 가 ", "다", "라"), 0, "해설"));
        assertInvalid(() -> workflow.editQuestion(preview, id, "질문", List.of("ABC", "ａｂｃ", "다", "라"), 0, "해설"));
        assertInvalid(() -> workflow.editQuestion(preview, id, "질문", List.of("가", " ", "다", "라"), 0, "해설"));
        assertInvalid(() -> workflow.editQuestion(preview, id, "질문", List.of("가", "나", "다", "라"), -1, "해설"));
        assertInvalid(() -> workflow.editQuestion(preview, id, "질문", List.of("가", "나", "다", "라"), 4, "해설"));
        assertInvalid(() -> workflow.editQuestion(preview, id, "질문", List.of("가", "나", "다", "라"), 0, ""));
        assertInvalid(() -> workflow.editQuestion(preview, "unknown", "질문", List.of("O", "X"), 0, "해설"));
        assertEquals(original, preview.set());
        assertFalse(Files.exists(temporary.getRoot().toPath().resolve("quizzes")));
    }

    private static void assertInvalid(Runnable action) {
        try {
            action.run();
            fail("잘못된 편집을 거부해야 합니다.");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isBlank());
        }
    }

    @Test public void rangeGenerationPreservesOriginalPdfPageAndLine() throws Exception {
        QuizWorkflow workflow = workflow();
        QuizWorkflow.Preview preview = workflow.generate(
                Path.of("samples", "b_behavior.pdf.pdf"), 9, 20);
        assertTrue(preview.diagnostics().contains("9페이지: 텍스트"));
        assertTrue(preview.set().questions().stream().allMatch(q ->
                q.page() != null && q.page() >= 9 && q.page() <= 20
                && q.lineNumber() != null && q.lineNumber() > 0));
        assertFalse(preview.set().questions().isEmpty());
        SavedQuizSet saved = workflow.saveReviewed(preview, Set.of());
        assertEquals(saved, workflow.loadQuiz(temporary.getRoot().toPath()
                .resolve("quizzes").resolve(saved.id() + ".json")));
    }

    @Test public void wrongAnswersAreStackedLastInFirstOutAcrossSavedReview() throws Exception {
        SavedQuizSet original = sampleSet();
        Path quizFile = quizzes().save(original);
        QuizWorkflow workflow = workflow();
        QuizSession first = workflow.start(original);
        assertFalse(first.submit("X").correct());
        assertTrue(first.submit("O").correct());
        first.next();
        assertFalse(first.submit("1").correct());
        assertTrue(first.submit("3").correct());
        assertEquals(List.of(original.questions().get(1), original.questions().get(0)),
                first.wrongQuestions());
        try {
            first.submit("2");
            fail("중복 채점은 거부해야 합니다.");
        } catch (IllegalStateException expected) {
            assertEquals(2, first.answers().size());
        }
        Path firstFile = workflow.saveResult(first);
        QuizWorkflow.Review restored = workflow.loadReview(quizFile, firstFile);
        assertEquals(first.wrongQuestions(), restored.wrong());
        QuizSession retry = workflow.startReview(restored);
        assertEquals(original.questions().get(1), retry.current());
        assertTrue(retry.submit("3").correct());
        retry.next();
        assertFalse(retry.submit("X").correct());
        assertTrue(retry.submit("O").correct());
        retry.next();
        Path retryFile = workflow.saveResult(retry);
        assertEquals(List.of(original.questions().get(0)),
                workflow.loadReview(quizFile, retryFile).wrong());
        assertEquals(2, retry.answers().size());
        assertFalse(firstFile.equals(retryFile));
    }

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private QuizRepository quizzes() {
        return new QuizRepository(temporary.getRoot().toPath().resolve("quizzes"));
    }

    private QuizResultRepository results() {
        return new QuizResultRepository(temporary.getRoot().toPath().resolve("results"));
    }

    private QuizWorkflow workflow() {
        return new QuizWorkflow(quizzes(), results(),
                Clock.fixed(Instant.parse("2026-11-11T01:00:00Z"), ZoneOffset.UTC),
                () -> new QuizGenerator(new Random(7)));
    }

    private SavedQuizSet sampleSet() {
        String source = "강의.pdf";
        return new SavedQuizSet(UUID.randomUUID().toString(), source, List.of(
                new SavedQuestion(UUID.randomUUID().toString(), SavedQuestion.Type.OX,
                        "O/X 질문", List.of("O", "X"), 0, "O/X 해설", source, 2, 4),
                new SavedQuestion(UUID.randomUUID().toString(), SavedQuestion.Type.MULTIPLE_CHOICE,
                        "4지선다 질문", List.of("가", "나", "다", "라"), 2,
                        "객관식 해설", source, 3, 5)));
    }

    @Test public void generatesRealPdfAndSavesOnlyReviewedQuestions() throws Exception {
        QuizWorkflow workflow = workflow();
        QuizWorkflow.Preview preview = workflow.generate(Path.of("sample.pdf"));
        long ox = preview.set().questions().stream().filter(q -> q.type() == SavedQuestion.Type.OX).count();
        long multiple = preview.set().questions().stream()
                .filter(q -> q.type() == SavedQuestion.Type.MULTIPLE_CHOICE).count();
        assertTrue(ox > 0);
        assertTrue(multiple > 0);
        String excluded = preview.set().questions().get(0).id();
        SavedQuizSet saved = workflow.saveReviewed(preview, Set.of(excluded));
        assertEquals(preview.set().questions().size() - 1, saved.questions().size());
        assertFalse(saved.questions().stream().anyMatch(q -> q.id().equals(excluded)));
        assertEquals(saved, workflow.loadQuiz(temporary.getRoot().toPath().resolve("quizzes")
                .resolve(saved.id() + ".json")));
    }

    @Test public void missingPdfAndInvalidReviewIdFailWithoutSaving() throws Exception {
        QuizWorkflow workflow = workflow();
        try {
            workflow.generate(temporary.getRoot().toPath().resolve("missing.pdf"));
            fail("없는 PDF는 오류여야 합니다.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("PDF"));
        }
        SavedQuizSet set = sampleSet();
        try {
            workflow.saveReviewed(new QuizWorkflow.Preview(set, List.of()), Set.of("unknown"));
            fail("없는 문항을 제외하면 안 됩니다.");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isBlank());
        }
        assertFalse(Files.exists(temporary.getRoot().toPath().resolve("quizzes")));
    }

    @Test public void loadsExistingJsonWithoutReorderingAndGradesBothTypes() throws Exception {
        SavedQuizSet set = sampleSet();
        Path file = quizzes().save(set);
        QuizWorkflow workflow = workflow();
        SavedQuizSet loaded = workflow.loadQuiz(file);
        assertEquals(set, loaded);
        assertEquals(List.of("가", "나", "다", "라"), loaded.questions().get(1).options());
        QuizSession session = workflow.start(loaded);
        assertEquals("O/X 질문", session.current().question());
        try {
            session.submit("3");
            fail("O/X에는 3을 답할 수 없습니다.");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isBlank());
        }
        assertTrue(session.submit("O").correct());
        session.next();
        assertEquals("4지선다 질문", session.current().question());
        assertTrue(session.submit("3").correct());
        session.next();
        assertTrue(session.isComplete());
        Path resultFile = workflow.saveResult(session);
        QuizResult restored = results().load(resultFile.toString(), loaded);
        assertEquals(2, restored.correctCount());
        assertEquals(100.0, workflow.summary(restored).completion(), 0.001);
        assertEquals(100.0, workflow.summary(restored).accuracy(), 0.001);
    }

    @Test public void earlyExitSavesPartialResultAndPreservesUnanswered() throws Exception {
        SavedQuizSet set = sampleSet();
        quizzes().save(set);
        QuizWorkflow workflow = workflow();
        QuizSession session = workflow.start(set);
        assertFalse(session.submit("X").correct());
        Path file = workflow.saveResult(session);
        QuizResult result = results().load(file.toString(), set);
        assertEquals(2, result.totalQuestions());
        assertEquals(1, result.answers().size());
        assertEquals(1, workflow.summary(result).unanswered());
        assertEquals(50.0, workflow.summary(result).completion(), 0.001);
        assertEquals(0.0, workflow.summary(result).accuracy(), 0.001);
        assertEquals(result, session.finish());
        try {
            session.submit("O");
            fail("종료된 세션에 추가로 답할 수 없습니다.");
        } catch (IllegalStateException expected) {
            assertFalse(expected.getMessage().isBlank());
        }
    }

    @Test public void reviewKeepsOriginalQuestionAndWritesNewResult() throws Exception {
        SavedQuizSet set = sampleSet();
        Path quizFile = quizzes().save(set);
        QuizWorkflow workflow = workflow();
        QuizSession first = workflow.start(set);
        first.submit("X");
        first.submit("O");
        first.next();
        first.submit("3");
        first.next();
        Path firstFile = workflow.saveResult(first);
        String before = Files.readString(firstFile, StandardCharsets.UTF_8);
        QuizWorkflow.Review review = workflow.loadReview(quizFile, firstFile);
        assertEquals(List.of(set.questions().get(0)), review.wrong());
        QuizSession retry = workflow.startReview(review);
        assertEquals(set.questions().get(0).id(), retry.current().id());
        assertEquals(set.questions().get(0).options(), retry.current().options());
        assertEquals(set.questions().get(0).sourcePdf(), retry.current().sourcePdf());
        assertTrue(retry.submit("O").correct());
        retry.next();
        Path secondFile = workflow.saveResult(retry);
        assertNotEquals(firstFile, secondFile);
        assertEquals(before, Files.readString(firstFile, StandardCharsets.UTF_8));
        QuizResult second = results().load(secondFile.toString(), set);
        assertEquals(first.finish().id(), second.reviewOfResultId());
        assertEquals(1, second.correctCount());
        assertTrue(second.wrongQuestions(set).isEmpty());
    }

    @Test public void emptyAndDamagedSavedFilesAreHandled() throws Exception {
        QuizWorkflow workflow = workflow();
        SavedQuizSet empty = new SavedQuizSet(UUID.randomUUID().toString(), "빈.pdf", List.of());
        Path emptyFile = quizzes().save(empty);
        assertTrue(workflow.loadQuiz(emptyFile).questions().isEmpty());
        QuizSession session = workflow.start(empty);
        Path resultFile = workflow.saveResult(session);
        assertEquals(0, workflow.summary(session.finish()).total());
        assertTrue(workflow.loadReview(emptyFile, resultFile).wrong().isEmpty());
        Files.writeString(resultFile, "{broken", StandardCharsets.UTF_8);
        try {
            workflow.loadReview(emptyFile, resultFile);
            fail("손상된 결과 파일을 거부해야 합니다.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("JSON"));
        }
        Files.writeString(emptyFile, "{broken", StandardCharsets.UTF_8);
        try {
            workflow.loadQuiz(emptyFile);
            fail("손상된 문제 파일을 거부해야 합니다.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("JSON"));
        }
    }
}

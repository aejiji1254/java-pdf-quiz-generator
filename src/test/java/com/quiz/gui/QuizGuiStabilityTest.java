package com.quiz.gui;

import static org.junit.Assert.*;

import com.quiz.QuizGenerator;
import com.quiz.QuizRepository;
import com.quiz.QuizResult;
import com.quiz.QuizResultRepository;
import com.quiz.SavedQuestion;
import com.quiz.SavedQuizSet;
import com.quiz.TermExtractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class QuizGuiStabilityTest {
    @Test public void immediateFeedbackHidesAnswerUntilSolved() {
        SavedQuizSet questions = set();
        SavedQuestion multiple = questions.questions().get(1);
        java.time.Instant at = java.time.Instant.parse("2026-11-11T00:00:00Z");
        String correct = QuizMakerFrame.answerFeedback(multiple,
                com.quiz.AnswerRecord.grade(multiple, "3", at));
        assertTrue(correct.contains("정답입니다!"));
        assertTrue(correct.contains(multiple.explanation()));
        String wrong = QuizMakerFrame.answerFeedback(multiple,
                com.quiz.AnswerRecord.grade(multiple, "1", at));
        assertEquals("오답입니다! 다시 선택하세요!", wrong);
        assertFalse(wrong.contains("3"));
        assertFalse(wrong.contains(multiple.explanation()));
    }

    @Test public void errorMessageShowsRootExceptionClassAndCause() {
        Throwable failure = new RuntimeException("worker failed",
                new NoClassDefFoundError("org/apache/pdfbox/Loader"));
        String message = QuizMakerFrame.errorMessage("PDF에서 문제를 생성할 수 없습니다.", failure);
        assertTrue(message.contains("java.lang.NoClassDefFoundError"));
        assertTrue(message.contains("org/apache/pdfbox/Loader"));
        assertFalse(message.contains("worker failed"));
        assertEquals("오류", QuizMakerFrame.errorMessage("오류", null));
    }

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Path quizFolder() {
        return temporary.getRoot().toPath().resolve("quizzes");
    }

    private Path resultFolder() {
        return temporary.getRoot().toPath().resolve("results");
    }

    private QuizWorkflow workflow() {
        return new QuizWorkflow(new QuizRepository(quizFolder()),
                new QuizResultRepository(resultFolder()),
                Clock.fixed(Instant.parse("2026-11-11T02:00:00Z"), ZoneOffset.UTC),
                () -> new QuizGenerator(new Random(9)));
    }

    private SavedQuizSet set() {
        String source = "강의.pdf";
        return new SavedQuizSet(UUID.randomUUID().toString(), source, List.of(
                new SavedQuestion(UUID.randomUUID().toString(), SavedQuestion.Type.OX,
                        "긴 질문".repeat(80), List.of("O", "X"), 0, "해설", source, 1, 3),
                new SavedQuestion(UUID.randomUUID().toString(),
                        SavedQuestion.Type.MULTIPLE_CHOICE, "객관식 질문",
                        List.of("가".repeat(180), "나".repeat(180),
                                "다".repeat(180), "라".repeat(180)), 2,
                        "설명", source, 2, 5)));
    }

    @Test public void windowCloseCancelKeepsSessionAndSaveWritesResult() throws Exception {
        QuizWorkflow workflow = workflow();
        SavedQuizSet set = set();
        new QuizRepository(quizFolder()).save(set);
        QuizSession session = workflow.start(set);
        session.submit("X");
        session.submit("X");
        assertFalse(workflow.closeSession(session, QuizWorkflow.CloseChoice.CANCEL));
        assertFalse(session.isFinished());
        assertFalse(Files.exists(resultFolder()));
        assertTrue(workflow.closeSession(session, QuizWorkflow.CloseChoice.SAVE));
        assertTrue(session.isFinished());
        Path saved = resultFolder().resolve(session.finish().id() + ".json");
        assertTrue(Files.isRegularFile(saved));
        QuizResult result = new QuizResultRepository(resultFolder()).load(saved.toString(), set);
        assertEquals(1, result.answers().size());
        assertEquals(2, (int) result.attemptCounts().get(set.questions().get(0).id()));
        assertTrue(result.solvedQuestionIds().isEmpty());
        assertEquals(2, result.totalQuestions());
    }

    @Test public void windowCloseDiscardDoesNotSaveAndExplicitEndDoes() throws Exception {
        QuizWorkflow workflow = workflow();
        SavedQuizSet set = set();
        QuizSession discarded = workflow.start(set);
        discarded.submit("O");
        assertTrue(workflow.closeSession(discarded, QuizWorkflow.CloseChoice.DISCARD));
        assertFalse(Files.exists(resultFolder()));
        QuizSession ended = workflow.start(set);
        ended.submit("X");
        Path file = workflow.saveResult(ended);
        assertTrue(Files.isRegularFile(file));
        assertEquals(1, new QuizResultRepository(resultFolder()).load(file.toString(), set)
                .answers().size());
    }

    @Test public void saveFailureCanBeRetriedWithoutLosingAnswers() throws Exception {
        SavedQuizSet set = set();
        Path blocked = temporary.newFile("blocked").toPath();
        QuizWorkflow broken = new QuizWorkflow(new QuizRepository(quizFolder()),
                new QuizResultRepository(blocked.resolve("results")),
                Clock.systemUTC(), QuizGenerator::new);
        QuizSession session = broken.start(set);
        session.submit("X");
        try {
            broken.closeSession(session, QuizWorkflow.CloseChoice.SAVE);
            fail("저장 실패를 전달해야 합니다.");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().isBlank());
        }
        assertEquals(1, session.answers().size());
        assertTrue(workflow().closeSession(session, QuizWorkflow.CloseChoice.SAVE));
        assertTrue(Files.isRegularFile(resultFolder().resolve(session.finish().id() + ".json")));
    }

    @Test public void canceledAndInvalidPdfSelectionPreserveUsableState() throws Exception {
        QuizWorkflow workflow = workflow();
        assertNull(workflow.validatePdfSelection(null));
        for (Path bad : List.of(temporary.newFile("wrong.txt").toPath(),
                temporary.getRoot().toPath().resolve("missing.pdf"),
                temporary.getRoot().toPath())) {
            try {
                workflow.validatePdfSelection(bad);
                fail("잘못된 선택은 거부되어야 합니다.");
            } catch (IOException expected) {
                assertFalse(expected.getMessage().isBlank());
            }
        }
        Path invalidPdf = temporary.newFile("invalid.pdf").toPath();
        Files.writeString(invalidPdf, "not a PDF");
        assertEquals(invalidPdf.toAbsolutePath().normalize(),
                workflow.validatePdfSelection(invalidPdf));
        try {
            workflow.generate(invalidPdf);
            fail("손상된 PDF 추출은 오류여야 합니다.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("PDF"));
        }
        assertFalse(workflow.generate(Path.of("sample.pdf")).set().questions().isEmpty());
    }

    @Test public void excludedQuestionDisappearsFromStoredSetProgressAndScoring() throws Exception {
        QuizWorkflow workflow = workflow();
        SavedQuizSet original = set();
        SavedQuestion excluded = original.questions().get(0);
        SavedQuizSet kept = workflow.saveReviewed(
                new QuizWorkflow.Preview(original, List.of()), Set.of(excluded.id()));
        assertEquals(List.of(original.questions().get(1)), kept.questions());
        SavedQuizSet loaded = workflow.loadQuiz(quizFolder().resolve(kept.id() + ".json"));
        QuizSession session = workflow.start(loaded);
        assertEquals(1, session.total());
        assertEquals(kept.questions().get(0).id(), session.current().id());
        assertTrue(session.submit("3").correct());
        session.next();
        QuizResult result = new QuizResultRepository(resultFolder()).load(
                workflow.saveResult(session).toString(), loaded);
        assertEquals(1, result.totalQuestions());
        assertEquals(1, result.correctCount());
        assertEquals(100.0, workflow.summary(result).completion(), 0.001);
        assertEquals(100.0, workflow.summary(result).accuracy(), 0.001);
        assertFalse(result.answers().stream().anyMatch(a -> a.questionId().equals(excluded.id())));
    }

    @Test public void longKoreanTextWrapsAndBlankDescriptionIsHandled() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea shortCell = QuizMakerFrame.reviewCell("짧은 문제", 300);
            JTextArea longCell = QuizMakerFrame.reviewCell("긴 한글 문제".repeat(80), 300);
            assertTrue(longCell.getLineWrap());
            assertTrue(longCell.getWrapStyleWord());
            assertTrue(longCell.getPreferredSize().height > shortCell.getPreferredSize().height);
            JTextArea option = QuizMakerFrame.optionText("긴 한글 보기".repeat(90));
            assertTrue(option.getLineWrap());
            assertTrue(option.getRows() > 2);
            assertEquals("설명 없음", QuizMakerFrame.optionText("").getText());
        });
        assertTrue(new TermExtractor().extract(List.of("용어\n: \n다음 용어\n: 설명"))
                .terms().stream().noneMatch(term -> term.description().isBlank()));
    }
}

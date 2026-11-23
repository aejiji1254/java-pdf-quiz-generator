package com.quiz.gui;

import com.quiz.QuizGenerator;
import com.quiz.QuizRepository;
import com.quiz.QuizResult;
import com.quiz.QuizResultRepository;
import com.quiz.SavedQuestion;
import com.quiz.SavedQuizSet;
import com.quiz.LegacyFixtures;
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
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class QuizRevisionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private QuizRepository quizzes;
    private QuizResultRepository results;
    private QuizWorkflow workflow;
    private SavedQuizSet original;
    private Path originalFile;
    private Path resultFile;
    private byte[] originalBytes;
    private byte[] resultBytes;
    private QuizResult originalResult;
    private QuizWorkflow.Review originalReview;

    private QuizWorkflow workflow(QuizRepository repository) {
        return new QuizWorkflow(repository, results,
                Clock.fixed(Instant.parse("2026-11-19T00:00:00Z"), ZoneOffset.UTC),
                () -> new QuizGenerator(new Random(7)));
    }

    @Before public void saveOriginalAndExistingResult() throws Exception {
        quizzes = new QuizRepository(temporary.getRoot().toPath().resolve("quizzes"));
        results = new QuizResultRepository(temporary.getRoot().toPath().resolve("results"));
        workflow = workflow(quizzes);
        originalFile = LegacyFixtures.copy("quiz-v1.json", temporary.getRoot().toPath()
                .resolve("quizzes").resolve(LegacyFixtures.SET_ID + ".json"));
        original = workflow.loadQuiz(originalFile);
        resultFile = LegacyFixtures.copy("result-v1.json", temporary.getRoot().toPath()
                .resolve("results").resolve(LegacyFixtures.RESULT_ID + ".json"));
        originalBytes = Files.readAllBytes(originalFile);
        resultBytes = Files.readAllBytes(resultFile);
        originalResult = results.load(resultFile.toString(), original);
        originalReview = workflow.loadReview(originalFile, resultFile);
    }

    /** 정상 저장, 거부, 취소에 해당하는 무저장, 저장 실패 모두 기존 파일과 복습이 동일해야 한다. */
    @After public void originalAndExistingResultsRemainExactlyUnchanged() throws Exception {
        assertArrayEquals(originalBytes, Files.readAllBytes(originalFile));
        assertArrayEquals(resultBytes, Files.readAllBytes(resultFile));
        SavedQuizSet reloadedOriginal = quizzes.load(originalFile.toString());
        assertEquals(original, reloadedOriginal);
        assertEquals(originalResult, results.load(resultFile.toString(), reloadedOriginal));
        assertEquals(originalReview, workflow.loadReview(originalFile, resultFile));
    }

    private QuizWorkflow.Preview preview() throws IOException {
        return new QuizWorkflow.Preview(workflow.loadQuiz(originalFile), List.of());
    }

    private void assertOnlyOriginalQuizExists() throws IOException {
        try (var files = Files.list(originalFile.getParent())) {
            assertEquals(List.of(originalFile), files.toList());
        }
    }

    private static void assertInvalid(Runnable action) {
        try {
            action.run();
            fail("잘못된 입력은 거부해야 합니다.");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isBlank());
        }
    }

    private void assertSaveRejected(QuizWorkflow.Preview preview, Set<String> excluded,
            boolean confirmed) throws IOException {
        try {
            workflow.saveRevision(original, preview, excluded, confirmed);
            fail("수정본 저장을 거부해야 합니다.");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isBlank());
        }
        assertOnlyOriginalQuizExists();
    }

    private void assertContentAndSource(SavedQuestion expected, SavedQuestion actual) {
        assertEquals(expected, new SavedQuestion(expected.id(), actual.type(), actual.question(),
                actual.options(), actual.correctIndex(), actual.explanation(), actual.sourcePdf(),
                actual.page(), actual.lineNumber(), actual.origin(), expected.derivedFromQuestionId()));
        assertEquals(expected.origin(), actual.origin());
    }

    @Test public void editsBothTypesDeletesAndSavesNewV2SetWithoutBreakingV1Results() throws Exception {
        QuizWorkflow.Preview preview = preview();
        SavedQuestion ox = original.questions().get(0);
        SavedQuestion mc = original.questions().get(1);
        preview = workflow.editQuestion(preview, ox.id(), "수정 O/X", List.of("O", "X"), 1, "수정 O/X 해설");
        preview = workflow.editQuestion(preview, mc.id(), "수정 객관식",
                List.of("새 라", "새 다", "새 나", "새 가"), 0, "수정 객관식 해설");
        Set<String> excluded = Set.of(original.questions().get(2).id());
        assertTrue(workflow.hasReviewChanges(original, preview, excluded));
        QuizWorkflow.SavedRevision saved = workflow.saveRevision(original, preview, excluded, false);
        assertNotEquals(original.id(), saved.set().id());
        assertEquals(originalFile.getParent().resolve(saved.set().id() + ".json"), saved.path());
        SavedQuizSet loaded = workflow.loadQuiz(saved.path());
        assertEquals(saved.set(), loaded);
        assertEquals(2, loaded.questions().size());
        Set<String> oldIds = original.questions().stream().map(SavedQuestion::id).collect(Collectors.toSet());
        assertTrue(loaded.questions().stream().noneMatch(q -> oldIds.contains(q.id())));
        assertEquals(2, loaded.questions().stream().map(SavedQuestion::id).distinct().count());
        for (int i = 0; i < 2; i++) assertContentAndSource(preview.set().questions().get(i), loaded.questions().get(i));
        assertTrue(Files.readString(saved.path()).contains("\"version\":2"));
        assertEquals(original.id(), loaded.derivedFromSetId());
        for (int i = 0; i < 2; i++)
            assertEquals(original.questions().get(i).id(), loaded.questions().get(i).derivedFromQuestionId());
        QuizSession session = workflow.start(loaded);
        assertTrue(session.submit("X").correct());
        session.next();
        assertTrue(session.submit("1").correct());
        session.next();
        assertTrue(session.isComplete());
        try {
            results.load(resultFile.toString(), loaded);
            fail("이전 결과를 수정본에 잘못 연결하면 안 됩니다.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("세트 ID"));
        }
    }

    @Test public void exclusionAloneCountsAsChangeAndRefreshesEvenUneditedQuestionIds() throws Exception {
        QuizWorkflow.Preview preview = preview();
        Set<String> excluded = Set.of(original.questions().get(0).id(), original.questions().get(1).id());
        assertTrue(workflow.hasReviewChanges(original, preview, excluded));
        QuizWorkflow.SavedRevision saved = workflow.saveRevision(original, preview, excluded, false);
        assertEquals(1, saved.set().questions().size());
        assertNotEquals(original.questions().get(2).id(), saved.set().questions().get(0).id());
        assertContentAndSource(original.questions().get(2), workflow.loadQuiz(saved.path()).questions().get(0));
    }

    @Test public void unchangedCopyNeedsConfirmationAndConfirmedCopyHasFreshIds() throws Exception {
        QuizWorkflow.Preview preview = preview();
        assertFalse(workflow.hasReviewChanges(original, preview, Set.of()));
        assertSaveRejected(preview, Set.of(), false);
        QuizWorkflow.SavedRevision saved = workflow.saveRevision(original, preview, Set.of(), true);
        assertNotEquals(original.id(), saved.set().id());
        SavedQuizSet loaded = workflow.loadQuiz(saved.path());
        for (int i = 0; i < original.questions().size(); i++) {
            assertContentAndSource(original.questions().get(i), loaded.questions().get(i));
            String newId = loaded.questions().get(i).id();
            assertTrue(original.questions().stream().noneMatch(q -> q.id().equals(newId)));
        }
    }

    @Test public void revertedEditsAreUnchangedAndRequireConfirmationAgain() throws Exception {
        SavedQuestion question = original.questions().get(0);
        QuizWorkflow.Preview edited = workflow.editQuestion(preview(), question.id(), "임시 변경",
                question.options(), 1, "임시 해설");
        assertTrue(workflow.hasReviewChanges(original, edited, Set.of()));
        QuizWorkflow.Preview reverted = workflow.editQuestion(edited, question.id(), question.question(),
                question.options(), question.correctIndex(), question.explanation());
        assertFalse(workflow.hasReviewChanges(original, reverted, Set.of()));
        assertSaveRejected(reverted, Set.of(), false);
    }

    @Test public void allExcludedOrEmptySetCannotBeSavedEvenWithConfirmation() throws Exception {
        QuizWorkflow.Preview preview = preview();
        Set<String> all = original.questions().stream().map(SavedQuestion::id).collect(Collectors.toSet());
        assertInvalid(() -> workflow.hasReviewChanges(original, preview, all));
        assertSaveRejected(preview, all, true);
        try {
            workflow.saveReviewed(preview, all);
            fail("생성 후 전체 문항 제외도 거부해야 합니다.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("최소 1개"));
        }
        SavedQuizSet empty = new SavedQuizSet(UUID.randomUUID().toString(), original.sourcePdf(), List.of());
        try {
            workflow.saveRevision(empty, new QuizWorkflow.Preview(empty, List.of()), Set.of(), true);
            fail("빈 수정본은 저장할 수 없습니다.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("최소 1개"));
        }
        assertOnlyOriginalQuizExists();
    }

    @Test public void invalidEditsAndUnknownExclusionsNeverWriteFiles() throws Exception {
        QuizWorkflow.Preview preview = preview();
        String id = original.questions().get(1).id();
        assertInvalid(() -> workflow.editQuestion(preview, id, " ", List.of("가", "나", "다", "라"), 0, "해설"));
        assertInvalid(() -> workflow.editQuestion(preview, id, "질문", List.of("가", " 가 ", "다", "라"), 0, "해설"));
        assertInvalid(() -> workflow.editQuestion(preview, id, "질문", List.of("가", "나", "다", "라"), 4, "해설"));
        assertInvalid(() -> workflow.editQuestion(preview, id, "질문", List.of("가", "나", "다", "라"), 0, ""));
        assertSaveRejected(preview, Set.of(UUID.randomUUID().toString()), true);
        assertEquals(original, preview.set());
    }

    @Test public void mismatchedOriginalOrQuestionIdsAreRejectedWithoutSaving() throws Exception {
        SavedQuizSet differentSet = new SavedQuizSet(UUID.randomUUID().toString(), original.sourcePdf(),
                original.questions());
        assertSaveRejected(new QuizWorkflow.Preview(differentSet, List.of()), Set.of(), true);
        SavedQuestion q = original.questions().get(0);
        SavedQuestion foreign = new SavedQuestion(UUID.randomUUID().toString(), q.type(), q.question(),
                q.options(), q.correctIndex(), q.explanation(), q.sourcePdf(), q.page(), q.lineNumber());
        SavedQuizSet foreignQuestion = new SavedQuizSet(original.id(), original.sourcePdf(),
                List.of(foreign, original.questions().get(1), original.questions().get(2)));
        assertSaveRejected(new QuizWorkflow.Preview(foreignQuestion, List.of()), Set.of(), true);
    }

    @Test public void saveFailureLeavesPreviewAvailableForRetryAndOriginalFilesIntact() throws Exception {
        QuizRepository failing = new QuizRepository(originalFile.getParent()) {
            @Override public Path save(SavedQuizSet set) throws IOException {
                throw new IOException("디스크 저장 실패");
            }
        };
        SavedQuestion q = original.questions().get(0);
        QuizWorkflow.Preview edited = workflow.editQuestion(preview(), q.id(), "수정 질문",
                q.options(), 1, "수정 해설");
        try {
            workflow(failing).saveRevision(original, edited, Set.of(), false);
            fail("저장 실패가 호출자에 전달되어야 합니다.");
        } catch (IOException expected) {
            assertEquals("디스크 저장 실패", expected.getMessage());
        }
        assertOnlyOriginalQuizExists();
        assertEquals("수정 질문", edited.set().questions().get(0).question());
        QuizWorkflow.SavedRevision saved = workflow.saveRevision(original, edited, Set.of(), false);
        assertEquals(saved.set(), workflow.loadQuiz(saved.path()));
    }

    @Test public void additionOnlyAndEditedMarkerRemovalCanSaveAndReviewAsNewRevision() throws Exception {
        QuizWorkflow.Preview p = workflow.addQuestion(preview(), SavedQuestion.Type.OX,
                "추가 문항", List.of("O", "X"), 1, "해설");
        SavedQuestion added = p.set().questions().getLast();
        p = workflow.editQuestion(p, added.id(), added.question(), added.options(), 1, "표시를 지운 해설");
        assertTrue(workflow.hasReviewChanges(original, p, Set.of()));
        assertFalse(workflow.hasReviewChanges(original, p, Set.of(added.id())));
        QuizWorkflow.SavedRevision saved = workflow.saveRevision(original, p, Set.of(), false);
        SavedQuizSet loaded = workflow.loadQuiz(saved.path());
        assertEquals(4, loaded.questions().size());
        assertEquals("표시를 지운 해설", loaded.questions().getLast().explanation());
        QuizSession session = new QuizSession(loaded, List.of(loaded.questions().getLast()), null, Clock.systemUTC());
        assertFalse(session.submit("O").correct());
        assertTrue(session.submit("X").correct());
        session.next();
        QuizWorkflow.Review review = workflow.loadReview(saved.path(), workflow.saveResult(session));
        assertEquals(List.of(loaded.questions().getLast()), review.wrong());
        QuizSession retry = workflow.startReview(review);
        assertTrue(retry.submit("X").correct());
        retry.next();
        assertTrue(workflow.loadReview(saved.path(), workflow.saveResult(retry)).wrong().isEmpty());
    }

    @Test public void newQuestionsWithSourceCoordinatesAndOriginalReorderingAreRejected() throws Exception {
        SavedQuestion q = original.questions().get(0);
        SavedQuestion withPage = new SavedQuestion(UUID.randomUUID().toString(), q.type(), q.question(),
                q.options(), 0, "접두어와 무관", q.sourcePdf(), 2, null);
        SavedQuestion withLine = new SavedQuestion(UUID.randomUUID().toString(), q.type(), q.question(),
                q.options(), 0, "해설", q.sourcePdf(), 2, 3);
        for (SavedQuestion extra : List.of(withPage, withLine)) {
            var questions = new java.util.ArrayList<>(original.questions());
            questions.add(extra);
            assertSaveRejected(new QuizWorkflow.Preview(new SavedQuizSet(original.id(), original.sourcePdf(), questions),
                    List.of()), Set.of(), true);
        }
        assertSaveRejected(new QuizWorkflow.Preview(new SavedQuizSet(original.id(), original.sourcePdf(),
                List.of(original.questions().get(1), q, original.questions().get(2))), List.of()), Set.of(), true);
        assertInvalid(() -> new SavedQuizSet(original.id(), original.sourcePdf(), List.of(q, q)));
        SavedQuestion wrongSource = new SavedQuestion(q.id(), q.type(), q.question(), q.options(), 0,
                q.explanation(), "다른 출처.pdf", null, null);
        assertInvalid(() -> new SavedQuizSet(original.id(), original.sourcePdf(), List.of(wrongSource)));
        SavedQuestion extraWrongSource = new SavedQuestion(UUID.randomUUID().toString(), q.type(), q.question(),
                q.options(), 0, q.explanation(), "다른 출처.pdf", null, null);
        var questions = new java.util.ArrayList<>(original.questions());
        questions.add(extraWrongSource);
        assertInvalid(() -> new SavedQuizSet(original.id(), original.sourcePdf(), questions));
    }
}

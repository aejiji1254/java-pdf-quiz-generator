package com.quiz.gui;

import static org.junit.Assert.*;

import com.quiz.QuizResult;
import com.quiz.QuizResultRepository;
import com.quiz.SavedQuestion;
import com.quiz.SavedQuizSet;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class QuizSessionRetryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC);

    private SavedQuizSet set() {
        String source = "강의.pdf";
        return new SavedQuizSet(UUID.randomUUID().toString(), source, List.of(
                new SavedQuestion(UUID.randomUUID().toString(), SavedQuestion.Type.OX,
                        "캐시는 빠른 저장소이다.", List.of("O", "X"), 0,
                        "캐시 설명", source, 1, 2),
                new SavedQuestion(UUID.randomUUID().toString(), SavedQuestion.Type.MULTIPLE_CHOICE,
                        "캐시의 역할은?", List.of("가", "나", "다", "라"), 2,
                        "역할 설명", source, 2, 3)));
    }

    @Test public void firstCorrectLocksQuestionAndAllowsNext() {
        SavedQuizSet set = set();
        QuizSession session = new QuizSession(set, set.questions(), null, clock);
        assertFalse(session.solved());
        try {
            session.next();
            fail("정답 전에는 넘어갈 수 없습니다.");
        } catch (IllegalStateException expected) { }
        assertTrue(session.submit("O").correct());
        assertTrue(session.solved());
        assertEquals(1, session.attempts());
        assertEquals(0, session.retries());
        assertTrue(session.answers().get(0).correct());
        try {
            session.submit("X");
            fail("해결 후 중복 클릭을 막아야 합니다.");
        } catch (IllegalStateException expected) { }
        session.next();
        assertEquals(1, session.index());
    }

    @Test public void wrongRetriesKeepFirstAnswerAndPushOnceForBothTypes() throws Exception {
        SavedQuizSet set = set();
        QuizSession session = new QuizSession(set, set.questions(), null, clock);
        assertFalse(session.submit("X").correct());
        assertFalse(session.solved());
        assertEquals("오답입니다! 다시 선택하세요!", QuizMakerFrame.answerFeedback(
                set.questions().get(0), session.answers().get(0)));
        try {
            session.next();
            fail("오답 뒤에는 넘어갈 수 없습니다.");
        } catch (IllegalStateException expected) { }
        assertFalse(session.submit("X").correct());
        assertEquals(2, session.attempts());
        assertEquals(1, session.retries());
        assertEquals(List.of(set.questions().get(0)), session.wrongQuestions());
        assertTrue(session.submit("O").correct());
        assertEquals(3, session.attempts());
        assertFalse(session.answers().get(0).correct());
        session.next();
        assertFalse(session.submit("1").correct());
        assertFalse(session.submit("2").correct());
        assertTrue(session.submit("3").correct());
        assertEquals(List.of(set.questions().get(1), set.questions().get(0)),
                session.wrongQuestions());
        assertEquals(2, session.answers().size());
        assertEquals(2, session.finish().wrongCount());
        assertEquals(2, session.finish().retriedSolvedCount());
        assertEquals(0, session.finish().unresolvedAttemptedCount());

        QuizResultRepository repository = new QuizResultRepository(
                temporary.getRoot().toPath().resolve("results"));
        Path file = repository.save(session.finish(), set);
        QuizResult loaded = repository.load(file.toString(), set);
        assertEquals(session.finish(), loaded);
        assertEquals(3, (int) loaded.attemptCounts().get(set.questions().get(0).id()));
        assertTrue(loaded.solvedQuestionIds().containsAll(List.of(
                set.questions().get(0).id(), set.questions().get(1).id())));
    }

    @Test public void closingDuringRetrySavesFirstWrongAndUnsolvedState() throws Exception {
        SavedQuizSet set = set();
        QuizSession session = new QuizSession(set, set.questions(), null, clock);
        session.submit("X");
        session.submit("X");
        QuizResultRepository repository = new QuizResultRepository(
                temporary.getRoot().toPath().resolve("results"));
        Path file = repository.save(session.finish(), set);
        QuizResult loaded = repository.load(file.toString(), set);
        assertEquals(1, loaded.answers().size());
        assertFalse(loaded.answers().get(0).correct());
        assertEquals(0, loaded.retriedSolvedCount());
        assertEquals(1, loaded.unresolvedAttemptedCount());
        assertEquals(2, (int) loaded.attemptCounts().get(set.questions().get(0).id()));
        assertTrue(loaded.solvedQuestionIds().isEmpty());
    }
}

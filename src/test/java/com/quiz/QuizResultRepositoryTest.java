package com.quiz;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class QuizResultRepositoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC);

    private SavedQuizSet set() {
        String pdf = "강의\\특수\"PDF.pdf";
        return new SavedQuizSet(UUID.randomUUID().toString(), pdf, List.of(
                new SavedQuestion(UUID.randomUUID().toString(), SavedQuestion.Type.OX,
                        "캐시?\n설명", List.of("O", "X"), 0, "해설", pdf, 1, 2),
                new SavedQuestion(UUID.randomUUID().toString(), SavedQuestion.Type.MULTIPLE_CHOICE,
                        "특징은?", List.of("가", "나", "다", "라"), 2, "정답 설명", pdf, 3, 4)));
    }

    private QuizResultRepository repository() {
        return new QuizResultRepository(temporary.getRoot().toPath().resolve("results"));
    }

    private QuizResult play(SavedQuizSet set, String input, ByteArrayOutputStream bytes) {
        return new QuizGenerator().playSavedQuestions(set, set.questions(), new Scanner(input),
                new PrintStream(bytes, true, StandardCharsets.UTF_8), clock, null);
    }

    @Test public void allCorrectMixedTypesAndSummary() {
        SavedQuizSet set = set();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        QuizResult result = play(set, "O\n3\n", output);
        assertEquals(2, result.correctCount());
        assertEquals(0, result.wrongCount());
        assertEquals(set.questions().get(0).id(), result.answers().get(0).questionId());
        assertEquals(clock.instant(), result.answers().get(0).answeredAt());
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("전체: 2문제, 정답 2, 오답 0"));
        assertTrue(text.contains("O/X 정답률: 100.0% (1/1)"));
        assertTrue(text.contains("4지선다 정답률: 100.0% (1/1)"));
    }

    @Test public void allWrongCanBeSelectedForReview() {
        SavedQuizSet set = set();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        QuizResult result = play(set, "X\n1\n", output);
        assertEquals(0, result.correctCount());
        assertEquals(2, result.wrongCount());
        assertEquals(set.questions(), result.wrongQuestions(set));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("정답률 0.0%"));
    }

    @Test public void interruptedRunRetainsTotalAndUnansweredCount() throws Exception {
        SavedQuizSet set = set();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        QuizResult result = play(set, "O\n", output);
        assertEquals(2, result.totalQuestions());
        assertEquals(1, result.answers().size());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("미응답 1"));
        QuizResultRepository repository = repository();
        assertEquals(result, repository.load(repository.save(result, set).toString(), set));
    }

    @Test public void emptySetHasZeroRatesAndCanRoundTrip() throws Exception {
        SavedQuizSet empty = new SavedQuizSet(UUID.randomUUID().toString(), "빈 강의.pdf", List.of());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        QuizResult result = play(empty, "", output);
        QuizResultRepository repository = repository();
        assertEquals(result, repository.load(repository.save(result, empty).toString(), empty));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("전체: 0문제"));
    }

    @Test public void savesAndReloadsKoreanEscapesAndExactAnswers() throws Exception {
        SavedQuizSet set = set();
        QuizResult result = play(set, "O\n1\n", new ByteArrayOutputStream());
        QuizResultRepository repository = repository();
        Path path = repository.save(result, set);
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(raw.contains("강의"));
        assertTrue(raw.contains("\\특수\\\"PDF.pdf"));
        assertTrue(raw.contains("\"correct\":false"));
        assertEquals(result, new QuizResultRepository(path.getParent()).load(path.getFileName().toString(), set));
        assertEquals(1, result.correctCount());
        assertEquals(1, result.wrongCount());
    }

    @Test public void rejectsCorruptionMissingFieldsAndWrongQuestion() throws Exception {
        SavedQuizSet set = set();
        QuizResult result = play(set, "O\n3\n", new ByteArrayOutputStream());
        QuizResultRepository repository = repository();
        Path path = repository.save(result, set);
        String valid = Files.readString(path, StandardCharsets.UTF_8);
        for (String invalid : List.of("{broken",
                valid.replace("\"answers\":", "\"missing\":"),
                valid.replace("\"correct\":true", "\"correct\":false"),
                valid.replace(set.questions().get(0).id(), UUID.randomUUID().toString()),
                valid.replace("\"version\":1", "\"version\":1,\"version\":1"))) {
            Files.writeString(path, invalid, StandardCharsets.UTF_8);
            try {
                repository.load(path.toString(), set);
                fail("손상된 기록을 거부해야 합니다.");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("JSON"));
            }
        }
    }

    @Test public void rejectsDifferentQuizSetMissingFileAndOverwrite() throws Exception {
        SavedQuizSet set = set();
        QuizResult result = play(set, "O\n3\n", new ByteArrayOutputStream());
        QuizResultRepository repository = repository();
        Path path = repository.save(result, set);
        try {
            repository.save(result, set);
            fail("덮어쓰기를 거부해야 합니다.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("이미"));
        }
        SavedQuizSet other = set();
        try {
            repository.load(path.toString(), other);
            fail("다른 문제 세트와 매칭되면 안 됩니다.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("ID"));
        }
        try {
            repository.load("missing.json", set);
            fail("없는 파일은 오류를 표시해야 합니다.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("찾을 수 없습니다"));
        }
    }

    @Test public void reviewCreatesNewResultWithoutReplacingOriginal() throws Exception {
        SavedQuizSet set = set();
        QuizResultRepository repository = repository();
        QuizResult first = play(set, "X\n3\n", new ByteArrayOutputStream());
        Path firstPath = repository.save(first, set);
        String before = Files.readString(firstPath);
        List<SavedQuestion> wrong = first.wrongQuestions(set);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        QuizResult review = new QuizGenerator().playSavedQuestions(set, wrong,
                new Scanner("O\n"), new PrintStream(output, true, StandardCharsets.UTF_8),
                clock, first.id());
        Path reviewPath = repository.save(review, set);
        assertNotEquals(firstPath, reviewPath);
        assertEquals(first.id(), review.reviewOfResultId());
        assertEquals(wrong.get(0).id(), review.answers().get(0).questionId());
        assertEquals(1, review.correctCount());
        assertEquals(before, Files.readString(firstPath));
        assertEquals(review, repository.load(reviewPath.toString(), set));
    }

    @Test public void jsonCodecEscapesSpecialCharactersAndRejectsInvalidEscape() {
        String value = "한글 \"따옴표\" \\ 역슬래시\n새 줄\t탭";
        assertEquals(value, QuizJson.parse(QuizJson.write(value)));
        try {
            QuizJson.parse("\"bad\\q\"");
            fail("잘못된 이스케이프는 거부해야 합니다.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("이스케이프"));
        }
    }
}

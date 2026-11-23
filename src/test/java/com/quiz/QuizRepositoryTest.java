package com.quiz;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class QuizRepositoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private SavedQuizSet sample() {
        String pdf = "sample.pdf";
        SavedQuestion ox = new SavedQuestion(UUID.randomUUID().toString(), SavedQuestion.Type.OX,
                "캐시는 빠른 저장소이다.", List.of("O", "X"), 0,
                "PDF의 캐시 설명", pdf, 2, 11);
        SavedQuestion choice = new SavedQuestion(UUID.randomUUID().toString(),
                SavedQuestion.Type.MULTIPLE_CHOICE, "캐시의 설명은?",
                List.of("보기 가", "보기 나", "정답 설명", "보기 라"), 2,
                "정답은 PDF의 설명과 일치합니다.", pdf, 3, 15);
        return new SavedQuizSet(UUID.randomUUID().toString(), pdf, List.of(ox, choice));
    }

    private QuizRepository repository() throws IOException {
        return new QuizRepository(temporary.newFolder("quizzes").toPath());
    }

    @Test public void roundTripPreservesOxMultipleChoiceAllFieldsAndUtf8() throws Exception {
        QuizRepository repository = repository();
        SavedQuizSet set = sample();
        Path path = repository.save(set);
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(raw.contains("캐시의 설명은?"));
        assertTrue(raw.contains("\"correctIndex\":2"));
        assertEquals(set, repository.load(path.getFileName().toString()));
        assertEquals(set, new QuizRepository(path.getParent()).load(path.toString()));
    }

    @Test public void generatedQuestionsRetainAvailablePdfPageAndTermLine() {
        TermExtractor.Result extracted = new TermExtractor().extract(List.of(
                "- First\n: First clear description\n- Second\n: Second clear description\n"
                + "- Third\n: Third clear description\n- Fourth\n: Fourth clear description"));
        QuizGenerator generator = new QuizGenerator(new java.util.Random(7));
        SavedQuizSet set = SavedQuizSet.fromGenerated("sample.pdf", extracted,
                generator.generateOx(extracted.terms()),
                generator.generateMultipleChoice(extracted).questions());
        assertFalse(set.questions().isEmpty());
        assertTrue(set.questions().stream().anyMatch(q -> q.type() == SavedQuestion.Type.MULTIPLE_CHOICE));
        assertTrue(set.questions().stream().allMatch(q ->
                q.page() != null && q.page() == 1 && q.lineNumber() != null && q.lineNumber() > 0));
    }

    @Test public void emptySetCanBeSavedAndLoaded() throws Exception {
        QuizRepository repository = repository();
        SavedQuizSet empty = new SavedQuizSet(UUID.randomUUID().toString(), "sample.pdf", List.of());
        assertEquals(empty, repository.load(repository.save(empty).toString()));
    }

    @Test public void refusesOverwrite() throws Exception {
        QuizRepository repository = repository();
        SavedQuizSet set = sample();
        Path path = repository.save(set);
        String before = Files.readString(path);
        try {
            repository.save(set);
            fail("덮어쓰기가 거부되어야 합니다.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("이미"));
        }
        assertEquals(before, Files.readString(path));
    }

    @Test public void rejectsBrokenJsonMissingFieldsAndWrongAnswerIndex() throws Exception {
        QuizRepository repository = repository();
        SavedQuizSet set = sample();
        Path path = repository.save(set);
        String valid = Files.readString(path, StandardCharsets.UTF_8);
        for (String broken : List.of("{broken", valid.replace("\"sourcePdf\":\"sample.pdf\",", ""),
                valid.replace("\"correctIndex\":2", "\"correctIndex\":4"),
                valid.replace("\"version\":2", "\"version\":2,\"version\":2"))) {
            Files.writeString(path, broken, StandardCharsets.UTF_8);
            try {
                repository.load(path.toString());
                fail("잘못된 JSON은 거부되어야 합니다.");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("JSON"));
            }
        }
    }

    @Test public void reportsMissingAndUnreadableFilesAndRejectsTraversal() throws Exception {
        QuizRepository repository = repository();
        for (String filename : List.of("missing.json", "../outside.json")) {
            try {
                repository.load(filename);
                fail("파일을 불러올 수 없어야 합니다.");
            } catch (IOException expected) {
                assertFalse(expected.getMessage().isBlank());
            }
        }
        Path directory = temporary.newFolder("directory.json").toPath();
        try {
            repository.load(directory.toString());
            fail("디렉터리는 읽을 수 없어야 합니다.");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().isBlank());
        }
    }

    @Test public void savedQuestionsCanBeAnsweredWithoutRegeneration() {
        SavedQuizSet set = sample();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new QuizGenerator().runSavedQuestions(set.questions(), new Scanner("O\n3\n"),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("점수: 2/2"));
        assertTrue(output.contains("③ 정답 설명"));
    }

    @Test public void invalidAnswerIsRetried() {
        SavedQuizSet set = sample();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new QuizGenerator().runSavedQuestions(set.questions(), new Scanner("q\nO\n9\n3\n"),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("O 또는 X를 입력하세요."));
        assertTrue(output.contains("1~4 사이의 번호를 입력하세요."));
        assertTrue(output.contains("점수: 2/2"));
    }
}

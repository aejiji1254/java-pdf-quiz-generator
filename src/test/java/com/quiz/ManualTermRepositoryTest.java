package com.quiz;

import static org.junit.Assert.*;

import com.quiz.gui.QuizWorkflow;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ManualTermRepositoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private ManualTermRepository repository() {
        return new ManualTermRepository(temporary.getRoot().toPath().resolve("manual"));
    }

    private QuizWorkflow workflow() {
        return new QuizWorkflow(new QuizRepository(temporary.getRoot().toPath().resolve("quizzes")),
                new QuizResultRepository(temporary.getRoot().toPath().resolve("results")),
                repository(), Clock.systemUTC(), () -> new QuizGenerator(new Random(7)));
    }

    @Test public void validatesBlankDuplicateAndTrimmedManualTerms() {
        ManualTermSet set = ManualTermSet.empty("sample.pdf");
        for (String[] pair : new String[][] {{" ", "설명"}, {"캐시", " "}}) {
            try {
                set.add(pair[0], pair[1]);
                fail("빈 항목은 거부해야 합니다.");
            } catch (IllegalArgumentException expected) { }
        }
        set = set.add(" 캐시 ", " 빠른 임시 저장소 ");
        assertEquals(new Term("캐시", "빠른 임시 저장소", 0), set.terms().get(0));
        try {
            set.add("캐시", "다른 설명");
            fail("중복 용어는 거부해야 합니다.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("중복"));
        }
    }

    @Test public void manualJsonRoundTripsAndQuizQuestionsKeepManualProvenance() throws Exception {
        ManualTermSet set = ManualTermSet.empty("강의.pdf")
                .add("캐시", "자주 쓰는 데이터를 빠르게 제공하는 임시 저장소")
                .add("버퍼", "입출력 속도 차이를 완화하도록 자료를 임시 보관하는 공간")
                .add("인덱스", "검색 위치를 빠르게 찾도록 구성된 참조 구조")
                .add("트랜잭션", "여러 작업을 하나의 논리적 단위로 처리하는 절차");
        Path manualFile = workflow().saveManual(set);
        assertTrue(Files.readString(manualFile, StandardCharsets.UTF_8).contains("트랜잭션"));
        assertEquals(set, workflow().loadManual(manualFile));
        QuizWorkflow.Preview preview = workflow().generateManual(set);
        assertFalse(preview.set().questions().isEmpty());
        assertTrue(preview.set().questions().stream().anyMatch(question ->
                question.type() == SavedQuestion.Type.MULTIPLE_CHOICE));
        assertTrue(preview.set().questions().stream().allMatch(question ->
                question.sourcePdf().startsWith("수동 입력")
                && question.page() == null && question.lineNumber() == null));
        SavedQuizSet saved = workflow().saveReviewed(preview, Set.of());
        assertEquals(saved, workflow().loadQuiz(temporary.getRoot().toPath()
                .resolve("quizzes").resolve(saved.id() + ".json")));
        assertFalse(new QuizGenerator(new Random(7)).generateOx(set.terms()).isEmpty());
        AnswerRecord grade = AnswerRecord.grade(saved.questions().get(0),
                saved.questions().get(0).type() == SavedQuestion.Type.OX ? "O" : "1",
                java.time.Instant.now());
        assertEquals(saved.questions().get(0).id(), grade.questionId());
    }

    @Test public void tooFewManualDescriptionsSkipMultipleChoiceAndBadJsonFails() throws Exception {
        ManualTermSet set = ManualTermSet.empty("").add("캐시", "빠른 임시 저장소의 역할을 한다")
                .add("버퍼", "자료를 잠시 보관하는 역할을 한다");
        QuizWorkflow.Preview preview = workflow().generateManual(set);
        assertEquals(0, preview.set().questions().stream()
                .filter(q -> q.type() == SavedQuestion.Type.MULTIPLE_CHOICE).count());
        assertTrue(preview.diagnostics().contains("설명 후보 3개"));
        Path file = repository().save(set);
        try {
            repository().save(set);
            fail("기존 파일을 덮어쓰면 안 됩니다.");
        } catch (java.nio.file.FileAlreadyExistsException expected) { }
        Files.writeString(file, "{broken", StandardCharsets.UTF_8);
        try {
            repository().load(file);
            fail("손상된 JSON을 거부해야 합니다.");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("JSON"));
        }
    }
}

package com.quiz;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Scanner;
import org.junit.Test;

public class MultipleChoiceTest {
    private final List<Term> terms = List.of(
            new Term("강화", "행동의 빈도를 높이는 결과", 1),
            new Term("처벌", "행동의 빈도를 낮추는 결과", 2),
            new Term("고전적 조건형성", "자극 사이의 연합을 학습", 3),
            new Term("조작적 조건형성", "행동과 결과의 관계를 학습", 4));

    @Test
    public void generatesConceptQuestionsWithFourGroundedDistinctOptionsAndOneAnswer() {
        QuizGenerator.MultipleChoiceResult result = new QuizGenerator(new Random(7))
                .generateMultipleChoice(terms);
        assertEquals(4, result.questions().size());
        assertTrue(result.skipped().stream().anyMatch(reason -> reason.contains("옳지 않은 것은?")));
        assertEquals(4, result.questions().stream()
                .filter(q -> q.type() == MultipleChoiceQuestion.Type.CORRECT).count());
        assertEquals(0, result.questions().stream()
                .filter(q -> q.type() == MultipleChoiceQuestion.Type.INCORRECT).count());
        for (MultipleChoiceQuestion question : result.questions()) {
            assertEquals(4, question.options().size());
            assertEquals(4, new HashSet<>(question.options()).size());
            assertTrue(question.correctIndex() >= 0 && question.correctIndex() < 4);
            Term anchor = terms.stream().filter(t -> question.prompt().contains("'" + t.term() + "'"))
                    .findFirst().get();
            assertEquals(anchor.description(), question.options().get(question.correctIndex()));
            for (int option = 0; option < 4; option++) {
                String text = question.options().get(option);
                Term source = terms.stream().filter(t -> t.description().equals(text)).findFirst().get();
                if (option != question.correctIndex()) {
                    assertNotEquals(anchor.term(), source.term());
                    assertTrue(question.explanation().contains("'" + source.term() + "'의 설명"));
                }
            }
        }
    }

    @Test
    public void randomPlacementIsReproducibleAndCanChange() {
        List<MultipleChoiceQuestion> first = new QuizGenerator(new Random(7))
                .generateMultipleChoice(terms).questions();
        assertEquals(first, new QuizGenerator(new Random(7)).generateMultipleChoice(terms).questions());
        assertNotEquals(first, new QuizGenerator(new Random(42)).generateMultipleChoice(terms).questions());
        assertTrue(first.stream().map(MultipleChoiceQuestion::correctIndex).distinct().count() > 1);
    }

    @Test
    public void skipsWhenEvidenceIsInsufficientOrAmbiguous() {
        QuizGenerator.MultipleChoiceResult tooFew = new QuizGenerator(new Random(1))
                .generateMultipleChoice(terms.subList(0, 3));
        assertTrue(tooFew.questions().isEmpty());
        assertTrue(tooFew.skipped().stream().anyMatch(reason -> reason.contains("4쌍 미만")));

        List<Term> onePage = terms.stream().map(t -> new Term(t.term(), t.description(), 1)).toList();
        QuizGenerator.MultipleChoiceResult samePage = new QuizGenerator(new Random(1))
                .generateMultipleChoice(onePage);
        assertEquals(4, samePage.questions().size());
        assertEquals(1, samePage.skipped().size());
        assertTrue(samePage.skipped().get(0).contains("옳지 않은 것은?"));

        List<Term> ambiguous = List.of(terms.get(0), terms.get(1), terms.get(2),
                new Term("중복", terms.get(2).description(), 4));
        assertTrue(new QuizGenerator(new Random(1)).generateMultipleChoice(ambiguous).questions().isEmpty());
        assertTrue(new QuizGenerator(new Random(1)).generateMultipleChoice(List.of(
                new Term("빈칸", "______", 1), terms.get(0), terms.get(1), terms.get(2)))
                .questions().isEmpty());
        assertTrue(new QuizGenerator(new Random(1)).generateMultipleChoice(List.of(
                new Term("합쳐진 항목", "첫 설명 - 다음 항목의 설명", 1),
                terms.get(0), terms.get(1), terms.get(2))).questions().isEmpty());
        assertTrue(new QuizGenerator(new Random(1)).generateMultipleChoice(List.of(
                new Term("반사적 행동에 한정되어 나타남", "고차원적 사고가 어려움", 1),
                terms.get(0), terms.get(1), terms.get(2))).questions().isEmpty());
        assertTrue(new QuizGenerator(new Random(1)).generateMultipleChoice(List.of(
                new Term("혼합된 설명", "다양한 환경 변화 의해 결정된다는 입장임", 1),
                terms.get(0), terms.get(1), terms.get(2))).questions().isEmpty());
    }

    @Test
    public void retriesInvalidAnswersAndReportsGradeAndExplanation() {
        String output = run(terms, "abc\n5\n1\n");
        assertTrue(output.contains("1~4 사이의 번호를 입력하세요."));
        assertTrue(output.contains("① "));
        assertTrue(output.contains("④ "));
        assertTrue(output.contains("정답: "));
        assertTrue(output.contains("해설: "));
        assertTrue(output.contains("입력이 종료되어 퀴즈를 중단합니다."));

        List<MultipleChoiceQuestion> questions = new QuizGenerator(new Random(7))
                .generateMultipleChoice(terms).questions();
        StringBuilder answers = new StringBuilder();
        for (MultipleChoiceQuestion question : questions) answers.append(question.correctIndex() + 1).append('\n');
        assertTrue(run(terms, answers.toString()).contains("점수: 4/4"));
    }

    @Test
    public void capsQuestionsAtThirty() {
        List<Term> many = java.util.stream.IntStream.range(0, 35)
                .mapToObj(i -> new Term("개념" + (char) ('가' + i),
                        "서로 다른 특징을 명시한 설명 " + String.format("%02d", i), i + 1)).toList();
        assertEquals(30, new QuizGenerator(new Random(1)).generateMultipleChoice(many)
                .questions().size());
    }

    @Test public void balancesEverySuccessfulBatchAndRecomputesAnswerAfterShuffling() {
        List<Term> many = java.util.stream.IntStream.range(0, 35)
                .mapToObj(i -> new Term("개념" + (char) ('가' + i),
                        "서로 다른 특징을 명시한 설명 " + String.format("%02d", i), i + 1)).toList();
        for (int seed = 0; seed < 50; seed++) {
            for (int size : List.of(4, 5, 7, 8, 15, 30, 35)) {
                List<Term> input = many.subList(0, size);
                QuizGenerator.MultipleChoiceResult result = new QuizGenerator(new Random(seed))
                        .generateMultipleChoice(input);
                assertEquals(Math.min(size, 30), result.questions().size());
                assertEquals(result, new QuizGenerator(new Random(seed)).generateMultipleChoice(input));
                int[] counts = new int[4];
                for (MultipleChoiceQuestion q : result.questions()) {
                    counts[q.correctIndex()]++;
                    assertEquals(q.original().description(), q.options().get(q.correctIndex()));
                    assertEquals(4, q.options().stream().map(SavedQuestion::optionKey).distinct().count());
                    assertEquals(1, q.options().stream().filter(q.original().description()::equals).count());
                }
                assertTrue(java.util.Arrays.stream(counts).max().getAsInt()
                        - java.util.Arrays.stream(counts).min().getAsInt() <= 1);
            }
        }
    }

    @Test public void rejectsNormalizedDuplicatesAndMultipleSourceAnswers() {
        List<Term> ambiguous = List.of(terms.get(0), terms.get(1), terms.get(2),
                new Term("다른 이름", "  자극  사이의 연합을 학습  ", 4), terms.get(3));
        QuizGenerator.MultipleChoiceResult result = new QuizGenerator(new Random(3))
                .generateMultipleChoice(ambiguous);
        assertEquals(3, result.candidateCount());
        assertTrue(result.questions().isEmpty());
        assertTrue(result.skipped().stream().anyMatch(s -> s.contains("정답 관계가 유일하지 않음")));
    }

    @Test
    public void samplePdfProducesGroundedConceptQuestions() throws Exception {
        List<Term> extracted = new TermExtractor().extract(new PdfReader().read(Path.of("sample.pdf"))).terms();
        QuizGenerator.MultipleChoiceResult result = new QuizGenerator(new Random(7))
                .generateMultipleChoice(extracted);
        assertTrue("sample.pdf에서 근거 있는 문제를 생성해야 합니다", !result.questions().isEmpty());
        assertTrue(result.questions().stream().allMatch(q -> q.type() == MultipleChoiceQuestion.Type.CORRECT));
        assertTrue(result.questions().stream().allMatch(q -> q.options().stream()
                .noneMatch(option -> option.contains(" - ") || option.contains("___"))));
        for (MultipleChoiceQuestion question : result.questions()) {
            assertEquals(4, new HashSet<>(question.options()).size());
            Term anchor = extracted.stream().filter(t -> question.prompt().contains("'" + t.term() + "'"))
                    .findFirst().get();
            assertEquals(anchor.description(), question.options().get(question.correctIndex()));
            for (int option = 0; option < 4; option++) {
                String text = question.options().get(option);
                assertEquals(1, extracted.stream().filter(t -> t.description().equals(text)).count());
                if (option != question.correctIndex()) assertNotEquals(anchor.description(), text);
            }
        }
        if (Boolean.getBoolean("quiz.preview")) {
            System.out.println("sample.pdf: 추출 용어 " + extracted.size() + "개, 객관식 "
                    + result.questions().size() + "개");
            String[] labels = {"①", "②", "③", "④"};
            for (int i = 0; i < result.questions().size(); i++) {
                MultipleChoiceQuestion question = result.questions().get(i);
                System.out.println("문제 " + (i + 1) + ": " + question.prompt());
                for (int option = 0; option < 4; option++) {
                    System.out.println(labels[option] + " " + question.options().get(option));
                }
                System.out.println("정답: " + labels[question.correctIndex()]);
                System.out.println("해설: " + question.explanation());
            }
            for (String reason : result.skipped()) System.out.println("건너뜀: " + reason);
        }
    }

    private static String run(List<Term> terms, String answers) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new QuizGenerator(new Random(7)).runMultipleChoice(terms, new Scanner(answers),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }
    @Test
    public void reportsEachExcludedSampleTermAndRestoresOnlyCompleteEvidence() throws Exception {
        TermExtractor.Result extracted = new TermExtractor().extract(
                new PdfReader().read(Path.of("sample.pdf")));
        QuizGenerator.MultipleChoiceResult result = new QuizGenerator(new Random(7))
                .generateMultipleChoice(extracted);
        assertEquals(18, extracted.terms().size());
        assertTrue(result.questions().size() > 0 && result.questions().size() <= 7);
        for (String name : List.of("개인과 환경 사이의 상호작용", "Watson 의 연구",
                "변별자극이 인간행동을 완전히 통제할 수 있는 것은 아님", "부적 강화",
                "Bandura &Walters(1963)", "대리적 조건화에 의한 학습",
                "반사적 행동에 한정되어 나타남",
                "치료기법 효과성 중시, 비교적 짧은 시간 내 개입효과 기대할 수 있음")) {
            assertTrue(name, result.skipped().stream().anyMatch(reason -> reason.contains(name)));
        }
        assertTrue(result.skipped().stream().anyMatch(reason ->
                reason.contains("복원: 18쪽") && reason.contains("대리적 조건화에 의한 학습")));
        assertTrue(result.skipped().stream().anyMatch(reason ->
                reason.contains("개인과 환경 사이의 상호작용")
                && reason.contains("완결된 설명을 확인할 수 없음")));
        for (String name : List.of("조작적 조건화", "대리적 강화")) {
            assertTrue(result.skipped().stream().anyMatch(reason ->
                    reason.contains("제외/검토 필요:") && reason.contains("'" + name + "'")));
        }
        MultipleChoiceQuestion restored = result.questions().stream().filter(question ->
                question.prompt().contains("'대리적 조건화에 의한 학습'")).findFirst().get();
        assertEquals("타인의 경험을 관찰함으로 이미 알고 있는 행동이 강화됨",
                restored.options().get(restored.correctIndex()));
        assertTrue(result.questions().stream().noneMatch(question ->
                question.prompt().contains("'조작적 조건화'")
                || question.prompt().contains("'대리적 강화'")));
        for (MultipleChoiceQuestion question : result.questions()) {
            assertEquals(4, new HashSet<>(question.options()).size());
            assertTrue(question.correctIndex() >= 0 && question.correctIndex() < 4);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new QuizGenerator(new Random(7)).runMultipleChoice(extracted, new Scanner(""),
                new PrintStream(output, true, StandardCharsets.UTF_8));
        String console = output.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("복원: 18쪽"));
        assertTrue(console.contains("제외/검토 필요: 2쪽"));
        assertTrue(console.contains("제외/검토 필요: 15쪽"));
        if (Boolean.getBoolean("quiz.audit")) {
            System.out.println("sample.pdf: 기존 8문항, 개선 후 " + result.questions().size() + "문항");
            for (String item : result.skipped()) System.out.println(item);
        }
    }

    @Test
    public void cannotRestoreIncompletePrefixBeforeBlank() {
        TermExtractor.Result extracted = new TermExtractor().extract(List.of(
                "- 개념\n: 미완성 변화\n3) ___\n뒤 문장\n"
                + "- 다른 개념\n: 설명이 완결된 내용"));
        QuizGenerator.MultipleChoiceResult result = new QuizGenerator(new Random(3))
                .generateMultipleChoice(extracted);
        assertTrue(result.skipped().stream().anyMatch(reason ->
                reason.contains("'개념'") && reason.contains("완결된 설명을 확인할 수 없음")));
        assertFalse(result.skipped().stream().anyMatch(reason -> reason.startsWith("복원:")));
    }

    @Test
    public void avoidsObviousDescriptionOverlapButDoesNotAssumeSemanticUniqueness() {
        List<Term> candidates = List.of(
                new Term("가", "행동을 더 자주 하게 하는 효과", 1),
                new Term("나", "행동을 더 자주 하게 하는 효과입니다", 2),
                new Term("다", "자극 사이의 연결을 학습하는 과정", 3),
                new Term("라", "모델의 행동을 관찰하는 과정", 4),
                new Term("마", "선택한 결과를 예측하는 신호", 5));
        QuizGenerator.MultipleChoiceResult result = new QuizGenerator(new Random(4))
                .generateMultipleChoice(candidates);
        assertTrue(result.questions().isEmpty());
        assertTrue(result.skipped().stream().anyMatch(reason -> reason.contains("매우 유사")));
        for (MultipleChoiceQuestion question : result.questions()) {
            assertFalse(question.options().contains(candidates.get(0).description())
                    && question.options().contains(candidates.get(1).description()));
        }
    }

}

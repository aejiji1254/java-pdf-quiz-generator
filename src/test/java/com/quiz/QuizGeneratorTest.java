package com.quiz;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.Scanner;
import org.junit.Test;

public class QuizGeneratorTest {
    private final List<Term> terms = List.of(
            new Term("강화", "행동의 빈도를 높이는 결과", 1),
            new Term("처벌", "행동의 빈도를 낮추는 결과", 2));

    @Test
    public void generatesOneGroundedFalseAndUsesOtherTermOnlyAsDonor() {
        List<QuizGenerator.OxQuestion> questions = new QuizGenerator(new Random(7)).generateOx(terms);
        assertEquals(1, questions.size());
        assertEquals(0, questions.stream().filter(QuizGenerator.OxQuestion::answer).count());
        for (QuizGenerator.OxQuestion q : questions) {
            assertNotEquals(q.original().description(), q.presentedDescription());
            assertEquals(q.descriptionSource().description(), q.presentedDescription());
            assertTrue(QuizGenerator.isDistractor(q.original(), q.descriptionSource()));
            assertEquals("다음 설명은 '" + q.original().term() + "'에 대한 설명이다.", q.statement());
        }
        assertEquals(questions, new QuizGenerator(new Random(7)).generateOx(terms));
    }

    @Test
    public void filtersOver100CharactersAndChecksRemainingCount() {
        Term boundary = new Term("경계", "가".repeat(100), 1);
        Term longTerm = new Term("긴 용어", "나".repeat(101), 1);
        QuizGenerator.OxResult result = new QuizGenerator(new Random(7)).generateOxDetailed(
                new TermExtractor.Result(List.of(boundary, terms.get(0), longTerm), List.of()));
        assertEquals(2, result.candidateCount());
        assertEquals(1, result.questions().size());
        assertTrue(result.skipped().stream().anyMatch(s -> s.contains("100자를 초과")));
        assertTrue(result.skipped().stream().anyMatch(s -> s.contains("설명 제공용")));
        assertEquals(1, new QuizGenerator().generateOx(List.of(boundary, longTerm)).size());
        assertTrue(new QuizGenerator().generateOx(List.of()).isEmpty());
        assertEquals("내용이 부족합니다", run(List.of(longTerm), "").strip());
    }

    @Test
    public void neverLabelsIdenticalDescriptionAsFalse() {
        List<QuizGenerator.OxQuestion> questions = new QuizGenerator().generateOx(List.of(
                new Term("가", "같은 의미로 반복되는 설명", 1),
                new Term("나", "같은 의미로 반복되는 설명", 2)));
        assertTrue(questions.isEmpty());
    }

    @Test
    public void gradesThenShowsOriginalExplanationAndRetriesInvalidInput() {
        List<QuizGenerator.OxQuestion> questions = new QuizGenerator(new Random(7)).generateOx(terms);
        StringBuilder answers = new StringBuilder("잘못된 입력\n");
        for (QuizGenerator.OxQuestion q : questions) answers.append(q.answer() ? "o\n" : "x\n");
        String output = run(terms, answers.toString());
        assertTrue(output.contains("O 또는 X를 입력하세요."));
        assertTrue(output.contains("점수: 1/1"));
        assertTrue(output.contains("정답: X"));
        assertTrue(output.indexOf("정답입니다.") < output.indexOf("해설:"));
        for (QuizGenerator.OxQuestion q : questions) {
            String beforeFeedback = output.substring(0, output.indexOf("정답입니다."));
            assertTrue(beforeFeedback.contains("설명: " + q.presentedDescription()));
            assertFalse(beforeFeedback.contains(q.original().description()));
            assertTrue(output.contains("해설: " + q.explanation()));
            assertTrue(q.explanation().contains(q.original().description()));
            assertTrue(q.explanation().contains(q.descriptionSource().term()));
            assertTrue(q.explanation().contains(q.descriptionSource().page() + "페이지"));
        }
        String wrongAnswers = answers.toString().replace("o\n", "TEMP\n").replace("x\n", "o\n").replace("TEMP\n", "x\n");
        assertTrue(run(terms, wrongAnswers).contains("점수: 0/1"));
        String stopped = run(terms, "");
        assertTrue(stopped.contains("퀴즈를 중단합니다"));
        assertFalse(stopped.contains("해설:"));
    }

    @Test public void mixedLanguageTermAndUnsafeFalseRemainNaturalAndReproducible() {
        List<Term> source = List.of(
                new Term("L2 캐시", "반복 데이터에 빠르게 접근하도록 임시 저장하는 공간", 1),
                new Term("버퍼", "입출력 속도 차이를 완화하도록 데이터를 임시 보관하는 공간", 2));
        List<QuizGenerator.OxQuestion> first = new QuizGenerator(new Random(5)).generateOx(source);
        assertEquals(first, new QuizGenerator(new Random(5)).generateOx(source));
        assertEquals(1, first.size());
        assertEquals(0, first.stream().filter(QuizGenerator.OxQuestion::answer).count());
        assertTrue(first.stream().anyMatch(q -> q.original().term().equals("L2 캐시") || q.descriptionSource().term().equals("L2 캐시")));
        first.forEach(q -> assertEquals("다음 설명은 '" + q.original().term() + "'에 대한 설명이다.", q.statement()));
    }

    @Test public void descriptionMentioningTheAnchorIsNotAnEligibleDistractor() {
        List<Term> source = List.of(
                new Term("캐시", "반복 데이터를 빠르게 제공하도록 임시 저장하는 공간", 1),
                new Term("버퍼", "캐시에 해당하지 않는다. 입출력 속도 차이를 완화하는 공간", 2));
        for (int seed = 0; seed < 20; seed++) {
            List<QuizGenerator.OxQuestion> generated =
                    new QuizGenerator(new Random(seed)).generateOx(source);
            assertEquals(2, generated.size());
            for (QuizGenerator.OxQuestion q : generated) {
                if (!q.answer()) {
                    fail("A를 명시한 설명은 필터에서 제외되어야 합니다.");
                }
            }
            assertTrue(generated.stream().allMatch(QuizGenerator.OxQuestion::answer));
            assertFalse(QuizGenerator.isDistractor(source.get(0), source.get(1)));
            QuizGenerator.OxResult detailed = new QuizGenerator(new Random(seed)).generateOxDetailed(new TermExtractor.Result(source, List.of()));
            assertTrue(detailed.skipped().stream().anyMatch(s -> s.contains("대체 설명 후보가 없음")));
            assertTrue(detailed.summary().contains("대체 설명 후보가 부족해 균형이 맞지 않음"));
        }
    }

    @Test public void oxDistributionIsBalancedForEvenOddAndInsufficientCandidates() {
        List<Term> many = java.util.stream.IntStream.range(0, 15)
                .mapToObj(i -> new Term("개념" + (char) ('가' + i),
                        "원본 자료에 기록된 고유한 설명 " + String.format("%02d", i), i + 1)).toList();
        for (int seed = 0; seed < 50; seed++) {
            for (int size = 0; size <= many.size(); size++) {
                List<Term> input = many.subList(0, size);
                QuizGenerator.OxResult result = new QuizGenerator(new Random(seed))
                        .generateOxDetailed(new TermExtractor.Result(input, List.of()));
                assertEquals(size == 2 ? 1 : size, result.questions().size());
                long positives = result.questions().stream().filter(QuizGenerator.OxQuestion::answer).count();
                assertTrue(Math.abs(2 * positives - result.questions().size()) <= 1);
                assertEquals(result.questions().size(), result.questions().stream()
                        .map(q -> SavedQuestion.optionKey(q.presentedDescription())).distinct().count());
                assertEquals(result.questions().size(), result.questions().stream().map(q -> q.original().term()).distinct().count());
                List<Term> fresh = input.stream().map(t -> new Term(t.term(), t.description(), t.page())).toList();
                assertEquals(result, new QuizGenerator(new Random(seed))
                        .generateOxDetailed(new TermExtractor.Result(fresh, List.of())));
                if (result.questions().size() % 2 == 1) assertTrue(result.summary().contains("홀수"));
                for (QuizGenerator.OxQuestion q : result.questions()) {
                    assertTrue(input.contains(q.original()));
                    assertEquals(q.descriptionSource().description(), q.presentedDescription());
                    assertEquals(q.answer(), q.original().description().equals(q.presentedDescription()));
                    assertEquals("다음 설명은 '" + q.original().term() + "'에 대한 설명이다.", q.statement());
                    assertTrue(q.presentedDescription().codePointCount(0, q.presentedDescription().length()) <= 100);
                    if (!q.answer()) assertTrue(QuizGenerator.isDistractor(q.original(), q.descriptionSource()));
                }
            }
        }
        QuizGenerator.OxResult excluded = new QuizGenerator(new Random(1)).generateOxDetailed(
                new TermExtractor.Result(List.of(new Term("불확실", "설명 ___ 누락", 1)), List.of()));
        assertTrue(excluded.questions().isEmpty());
        assertTrue(excluded.skipped().stream().anyMatch(s -> s.contains("빈칸")));
    }

    @Test public void bothQuestionTypesRejectMergedAndMalformedDescriptions() {
        List<Term> source = List.of(
                new Term("캐시", "반복 데이터를 빠르게 제공하는 임시 저장소", 1),
                new Term("버퍼", "입출력 속도 차이를 완화하며 데이터를 보관하는 공간", 1),
                new Term("색인", "검색할 위치를 찾도록 정렬한 참조 구조", 1),
                new Term("정렬", "자료를 일정한 순서에 따라 배치하는 과정", 1),
                new Term("짧음", "매우 짧음", 1),
                new Term("합쳐짐", "처음 항목의 설명 • 다음 항목의 별도 설명", 1),
                new Term("웹주소", "자세한 내용은 https://example.com 참고", 1),
                new Term("빈칸", "설명 중 ___ 부분이 비어 있는 자료", 1));
        QuizGenerator generator = new QuizGenerator(new Random(3));
        QuizGenerator.OxResult ox = generator.generateOxDetailed(new TermExtractor.Result(source, List.of()));
        QuizGenerator.MultipleChoiceResult multiple = generator.generateMultipleChoice(source);
        assertEquals(4, ox.questions().size());
        assertEquals(4, multiple.candidateCount());
        for (String name : List.of("짧음", "합쳐짐", "웹주소", "빈칸")) {
            assertTrue(ox.skipped().stream().anyMatch(reason -> reason.contains("'" + name + "'")));
            assertTrue(multiple.skipped().stream().anyMatch(reason -> reason.contains("'" + name + "'")));
        }
    }

    private String run(List<Term> source, String answers) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new QuizGenerator(new Random(7)).runOx(source, new Scanner(answers),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }
}

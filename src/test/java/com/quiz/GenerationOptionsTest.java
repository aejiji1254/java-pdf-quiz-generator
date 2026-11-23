package com.quiz;

import com.quiz.GenerationOptions.Direction;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class GenerationOptionsTest {
    public static List<Term> terms(int size) {
        List<Term> terms = new ArrayList<>();
        for (int i = 0; i < size; i++) terms.add(new Term("개념" + (char)('가' + i),
                "서로 구분되는 고유 기능 표지 " + (char)('나' + i) + "의 수행 과정", i + 1));
        return terms;
    }
    private static TermExtractor.Result extracted(List<Term> terms) { return new TermExtractor.Result(terms, List.of()); }

    @Test public void directionsCountsPositionsAndFreshObjectReproducibilityAcross50Seeds() {
        for (int seed = 0; seed < 50; seed++) for (int size : new int[] {4, 5, 7, 8, 15, 30, 35})
            for (Direction direction : Direction.values()) {
                int request = switch (seed % 5) { case 0 -> 0; case 1 -> 1; case 2 -> 7; case 3 -> 30; default -> 500; };
                var first = new QuizGenerator(new Random(seed)).generateMultipleChoice(terms(size), request, direction);
                var second = new QuizGenerator(new Random(seed)).generateMultipleChoice(terms(size), request, direction);
                assertEquals(first, second); // terms()는 매번 새 Term 객체를 만든다.
                assertEquals(Math.min(size, request), first.questions().size());
                int[] positions = new int[4];
                int reverse = 0;
                Set<String> anchors = new LinkedHashSet<>();
                for (var q : first.questions()) {
                    assertTrue(anchors.add(q.original().term()));
                    assertEquals(4, q.options().stream().map(SavedQuestion::optionKey).distinct().count());
                    positions[q.correctIndex()]++;
                    boolean reversed = QuizGenerator.reverse(q);
                    if (reversed) reverse++;
                    assertEquals(reversed ? q.original().term() : q.original().description(), q.options().get(q.correctIndex()));
                    assertEquals(1, q.options().stream().filter(option -> SavedQuestion.optionKey(option)
                            .equals(SavedQuestion.optionKey(reversed ? q.original().term() : q.original().description()))).count());
                    if (reversed) {
                        assertFalse(QuizGenerator.exposes(q.prompt(), q.original().term()));
                        assertTrue(q.prompt().endsWith(q.original().description()));
                    }
                    for (int i = 0; i < 4; i++) if (i != q.correctIndex()) {
                        String option = q.options().get(i);
                        Term source = terms(size).stream().filter(t -> (reversed ? t.term() : t.description()).equals(option)).findFirst().orElseThrow();
                        assertTrue(QuizGenerator.isDistractor(q.original(), source));
                        if (reversed) {
                            assertFalse(QuizGenerator.exposes(q.prompt(), source.term()));
                            assertTrue(q.explanation().contains(new String[] {"①", "②", "③", "④"}[i] + ": '" + source.term()
                                    + "'는 (" + source.description() + ")에 연결된 용어입니다."));
                        }
                    }
                }
                assertTrue(Arrays.stream(positions).max().orElseThrow() - Arrays.stream(positions).min().orElseThrow() <= 1);
                if (direction == Direction.FORWARD) assertEquals(0, reverse);
                if (direction == Direction.REVERSE) assertEquals(first.questions().size(), reverse);
                if (direction == Direction.MIXED) assertTrue(Math.abs(2 * reverse - first.questions().size()) <= 1);
            }
    }

    @Test public void forwardDefaultOverloadConsumesExactlyLegacyRandomSequence() {
        for (int seed = 0; seed < 50; seed++) {
            Random a = new Random(seed), b = new Random(seed);
            assertEquals(new QuizGenerator(a).generateMultipleChoice(terms(35)),
                    new QuizGenerator(b).generateMultipleChoice(terms(35), 30, Direction.FORWARD));
            assertEquals(a.nextLong(), b.nextLong());
        }
    }

    @Test public void oxNumericSubsetIsUniqueBalancedAndDeterministicAcross50SeedsAndSizes() {
        for (int seed = 0; seed < 50; seed++) for (int size = 0; size <= 15; size++) {
            int request = seed % 18;
            var result = new QuizGenerator(new Random(seed)).generateOxDetailed(extracted(terms(size)), request);
            var fresh = new QuizGenerator(new Random(seed)).generateOxDetailed(extracted(terms(size)), request);
            assertEquals(result, fresh);
            OxAllocationTest.unique(result);
            assertTrue(result.questions().size() <= request);
            long o = result.questions().stream().filter(QuizGenerator.OxQuestion::answer).count();
            assertTrue(Math.abs(2 * o - result.questions().size()) <= 1);
            assertTrue(result.skipped().stream().anyMatch(s -> s.contains("요청 " + request + "개, 생성")));
        }
    }

    @Test public void oxAllPreservesImbalanceButExplicitNumberPrioritizesBalance() {
        List<Term> incompatible = java.util.stream.IntStream.range(1, 7).mapToObj(i ->
                new Term("가".repeat(i), "서로 다른 고유 기능 설명 " + i, i)).toList();
        var legacy = new QuizGenerator(new Random(7)).generateOxDetailed(extracted(incompatible));
        var all = new QuizGenerator(new Random(7)).generateOxDetailed(extracted(incompatible), null);
        assertEquals(legacy, all); assertEquals(6, all.questions().size());
        assertTrue(all.summary().contains("대체 설명 후보가 부족해 균형이 맞지 않음"));
        var numeric = new QuizGenerator(new Random(7)).generateOxDetailed(extracted(incompatible), 6);
        assertEquals(1, numeric.questions().size()); assertTrue(numeric.questions().getFirst().answer());
        assertTrue(numeric.skipped().stream().anyMatch(s -> s.contains("후보 또는 균형")));
        var odd = new QuizGenerator(new Random(7)).generateOxDetailed(extracted(terms(8)), 5);
        assertEquals(5, odd.questions().size());
        assertEquals(3, odd.questions().stream().filter(QuizGenerator.OxQuestion::answer).count());
        Random fullRandom = new Random(7), limitedRandom = new Random(7);
        new QuizGenerator(fullRandom).generateOxDetailed(extracted(terms(8)));
        new QuizGenerator(limitedRandom).generateOxDetailed(extracted(terms(8)), 5);
        assertEquals("부분집합과 홀수 배정은 추가 난수를 소비하지 않는다", fullRandom.nextLong(), limitedRandom.nextLong());
    }

    @Test public void reverseExposureHandlesSpacesPunctuationWidthCaseAndFixedPrompt() {
        for (String[] pair : List.of(new String[] {"대리적 강화", "대리적강화"}, new String[] {"ＡＢＣ", "abc"},
                new String[] {"대리적 강화", "대리적·강화"}, new String[] {"다음 설명", "고정 문구와 별개의 정의"})) {
            List<Term> input = new ArrayList<>(terms(7));
            Term leaked = new Term(pair[0], pair[1] + "를 포함한 서로 다른 대상의 작동 과정", 1);
            input.add(leaked);
            var reverse = new QuizGenerator(new Random(7)).generateMultipleChoice(input, 500, Direction.REVERSE);
            assertTrue(reverse.questions().stream().noneMatch(q -> q.original().equals(leaked)));
            assertTrue(reverse.skipped().stream().anyMatch(s -> s.contains("노출로 제외된 앵커 1개")));
            var mixed = new QuizGenerator(new Random(7)).generateMultipleChoice(input, 500, Direction.MIXED);
            assertTrue(mixed.questions().stream().anyMatch(q -> q.original().equals(leaked) && !QuizGenerator.reverse(q)));
            assertTrue(mixed.questions().stream().anyMatch(q -> QuizGenerator.reverse(q)));
        }
        List<Term> input = new ArrayList<>(terms(6));
        Term anchor = new Term("독립 용어", "대리적강화를 관찰하여 반응을 습득하는 과정", 2);
        input.add(anchor); input.add(new Term("대리적 강화", "사회적 장면에서 타인의 결과를 활용하는 절차", 3));
        var result = new QuizGenerator(new Random(8)).generateMultipleChoice(input, 500, Direction.REVERSE);
        var question = result.questions().stream().filter(q -> q.original().equals(anchor)).findFirst().orElseThrow();
        assertFalse(question.options().contains("대리적 강화"));
    }

    @Test public void missingCandidatesInvalidOptionsAndManualPageLabel() {
        var missing = new QuizGenerator(new Random(7)).generateMultipleChoice(terms(3), 30, Direction.REVERSE);
        assertTrue(missing.questions().isEmpty()); assertFalse(missing.skipped().isEmpty());
        for (Runnable invalid : List.<Runnable>of(() -> new GenerationOptions(-1, 30, Direction.FORWARD),
                () -> new GenerationOptions(null, 501, Direction.MIXED), () -> new GenerationOptions(0, 0, null),
                () -> new QuizGenerator().generateMultipleChoice(terms(4), -1, Direction.FORWARD))) {
            try { invalid.run(); fail("잘못된 옵션 거부"); } catch (IllegalArgumentException expected) { }
        }
        var manual = terms(4).stream().map(t -> new Term(t.term(), t.description(), 0)).toList();
        for (var q : new QuizGenerator(new Random(7)).generateMultipleChoice(manual, 30, Direction.REVERSE).questions()) {
            assertTrue(q.explanation().contains("페이지 없음")); assertFalse(q.explanation().contains("0페이지"));
        }
    }

    @Test public void reverseKeepsFull180CharacterDistractorExplanationsAndPrefersSimilarTermLengths() {
        List<Term> input = new ArrayList<>();
        for (int i = 0; i < 7; i++) input.add(new Term("명".repeat(i + 1) + (char)('가' + i),
                String.valueOf((char)('마' + i)).repeat(180), 0));
        var result = new QuizGenerator(new Random(7)).generateMultipleChoice(input, 500, Direction.REVERSE);
        assertEquals(7, result.questions().size());
        for (var q : result.questions()) {
            assertTrue(q.prompt().endsWith(q.original().description()));
            List<Integer> expectedDistances = input.stream().filter(t -> !t.equals(q.original()))
                    .map(t -> Math.abs(t.term().length() - q.original().term().length())).sorted().limit(3).toList();
            List<Integer> actualDistances = new ArrayList<>();
            for (int i = 0; i < 4; i++) if (i != q.correctIndex()) {
                String option = q.options().get(i);
                Term source = input.stream().filter(t -> t.term().equals(option)).findFirst().orElseThrow();
                assertTrue(q.explanation().contains(source.description()));
                actualDistances.add(Math.abs(source.term().length() - q.original().term().length()));
            }
            Collections.sort(actualDistances); assertEquals(expectedDistances, actualDistances);
        }
    }
}

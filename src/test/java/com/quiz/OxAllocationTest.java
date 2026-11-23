package com.quiz;

import com.quiz.gui.QuizWorkflow;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class OxAllocationTest {
    static List<Term> graph(boolean[][] edges) {
        List<Term> result = new ArrayList<>();
        for (int i = 0; i < edges.length; i++) {
            StringBuilder description = new StringBuilder("서로 구분되는 고유 기능 표지 " + (char) ('A' + i) + ". ");
            for (int j = 0; j < edges.length; j++) if (j != i && !edges[i][j]) description.append("명칭").append((char) ('가' + j)).append(' ');
            result.add(new Term("명칭" + (char) ('가' + i), description.toString().strip(), i + 1));
        }
        return result;
    }

    static void unique(QuizGenerator.OxResult result) {
        assertEquals(result.questions().size(), result.questions().stream().map(q -> SavedQuestion.optionKey(q.presentedDescription())).distinct().count());
        assertEquals(result.questions().size(), result.questions().stream().map(q -> q.original().term()).distinct().count());
        for (var q : result.questions()) {
            assertEquals(q.descriptionSource().description(), q.presentedDescription());
            assertEquals(q.answer(), q.original().equals(q.descriptionSource()));
            if (!q.answer()) assertTrue(QuizGenerator.isDistractor(q.original(), q.descriptionSource()));
        }
    }

    @Test public void incompatibleCandidatesStayTrueAndExplainUnbalancedDistribution() {
        List<Term> all = java.util.stream.IntStream.range(1, 16).mapToObj(i ->
                new Term("가".repeat(i), "서로 다른 고유 기능 설명 " + String.format("%02d", i), i)).toList();
        for (int seed = 0; seed < 50; seed++) {
            for (int size = 0; size <= 15; size++) {
            List<Term> source = all.subList(0, size);
            var result = new QuizGenerator(new Random(seed)).generateOxDetailed(new TermExtractor.Result(source, List.of()));
            assertEquals(size, result.questions().size());
            assertTrue(result.questions().stream().allMatch(QuizGenerator.OxQuestion::answer));
            assertEquals(size > 1, result.summary().contains("대체 설명 후보가 부족해 균형이 맞지 않음"));
            assertEquals(size, result.skipped().stream().filter(s -> s.contains("대체 설명 후보가 없음")).count());
            unique(result);
            List<Term> fresh = source.stream().map(t -> new Term(t.term(), t.description(), t.page())).toList();
            assertEquals(result, new QuizGenerator(new Random(seed)).generateOxDetailed(new TermExtractor.Result(fresh, List.of())));
            }
        }
    }

    @Test public void bipartiteCycleUsesOneDonorToKeepUniqueDescriptionsAndBalance() {
        boolean[][] edges = new boolean[6][6];
        for (int[] edge : List.of(new int[] {0, 1}, new int[] {1, 2}, new int[] {2, 3}, new int[] {3, 0}))
            edges[edge[0]][edge[1]] = edges[edge[1]][edge[0]] = true;
        List<Term> source = graph(edges);
        for (int a = 0; a < 6; a++) for (int b = 0; b < 6; b++) assertEquals(edges[a][b], QuizGenerator.isDistractor(source.get(a), source.get(b)));
        for (int seed = 0; seed < 50; seed++) {
            var result = new QuizGenerator(new Random(seed)).generateOxDetailed(new TermExtractor.Result(source, List.of()));
            assertEquals(6, result.candidateCount()); assertEquals(5, result.questions().size());
            assertEquals(3, result.questions().stream().filter(q -> !q.answer()).count());
            assertEquals(1, result.skipped().stream().filter(s -> s.contains("설명 제공용")).count());
            assertFalse(result.summary().contains("균형이 맞지 않음"));
            unique(result);
            List<Term> fresh = source.stream().map(t -> new Term(t.term(), t.description(), t.page())).toList();
            assertEquals(result, new QuizGenerator(new Random(seed)).generateOxDetailed(new TermExtractor.Result(fresh, List.of())));
        }
    }

    @Test public void insufficientCrossEdgesKeepQuestionsRatherThanInventingAlternatives() {
        boolean[][] edges = new boolean[6][6]; edges[0][1] = edges[1][0] = true;
        var result = new QuizGenerator(new Random(7)).generateOxDetailed(new TermExtractor.Result(graph(edges), List.of()));
        assertEquals(6, result.questions().size()); assertEquals(2, result.questions().stream().filter(q -> !q.answer()).count());
        assertTrue(result.summary().contains("균형이 맞지 않음"));
        assertTrue(result.skipped().stream().anyMatch(s -> s.contains("O/X 균형 제한")));
        unique(result);
    }

    @Test public void supplementaryCodePointsAndLongOrMalformedDonorsAreChecked() {
        Term boundary = new Term("경계", "😀".repeat(50) + "나".repeat(50), 1);
        Term longTerm = new Term("초과", "😀".repeat(50) + "다".repeat(51), 2);
        Term malformed = new Term("빈칸", "설명이 ___ 빠진 자료입니다", 3);
        Term valid = new Term("정상", "일반적인 고유 동작을 설명하는 문장", 4);
        var result = new QuizGenerator(new Random(7)).generateOxDetailed(new TermExtractor.Result(List.of(boundary, longTerm, malformed, valid), List.of()));
        assertEquals(2, result.candidateCount()); assertEquals(1, result.questions().size());
        var q = result.questions().getFirst();
        assertEquals(Set.of(boundary, valid), Set.of(q.original(), q.descriptionSource()));
        assertTrue(result.skipped().stream().anyMatch(s -> s.contains("100자를 초과")));
        assertTrue(result.skipped().stream().anyMatch(s -> s.contains("빈칸")));
        unique(result);
    }

    @Test public void equallyBalancedPlansPreferTheClosestDescriptionLengths() {
        List<Term> source = List.of(new Term("알파", "가".repeat(12), 1), new Term("베타", "나".repeat(13), 2),
                new Term("감마", "다".repeat(60), 3), new Term("델타", "라".repeat(70), 4));
        for (int seed = 0; seed < 50; seed++) {
            var result = new QuizGenerator(new Random(seed)).generateOxDetailed(new TermExtractor.Result(source, List.of()));
            assertEquals(4, result.questions().size());
            var falseQuestions = result.questions().stream().filter(q -> !q.answer()).toList();
            assertEquals(2, falseQuestions.size());
            assertEquals(Set.of("알파", "베타"), new java.util.HashSet<>(falseQuestions.stream().map(q -> q.original().term()).toList()));
            unique(result);
        }
    }

    @Test public void restoredDescriptionsAreDeduplicatedAfterNormalization() {
        var extracted = new TermExtractor().extract(List.of("- 알파\n: 동일한 원본 설명으로 행동이 강화됨\n3) ___\n뒤에 합쳐진 알파 문장\n"
                + "- 베타\n: 동일한 원본 설명으로 행동이 강화됨\n3) ___\n뒤에 합쳐진 베타 문장"));
        var result = new QuizGenerator(new Random(7)).generateOxDetailed(extracted);
        assertTrue(result.questions().isEmpty());
        assertEquals(2, result.skipped().stream().filter(s -> s.contains("정규화 후 중복된 제시 설명")).count());
    }

    @Test public void savedQuestionsKeepAProvenanceAndBExplanationWithoutChangingQuestionStructure() {
        var extracted = new TermExtractor().extract(List.of("\n\n- 알파\n: 분리된 원본 자료를 보관하는 공간", "\n\n\n- 베타\n: 변형된 다른 동작을 수행하는 과정"), 9);
        var ox = new QuizGenerator(new Random(7)).generateOxDetailed(extracted);
        SavedQuizSet set = SavedQuizSet.fromGenerated("source.pdf", extracted, ox.questions(), List.of());
        assertEquals(1, set.questions().size());
        var q = ox.questions().getFirst(); var saved = set.questions().getFirst();
        assertEquals(q.statement() + "\n\n설명: " + q.presentedDescription(), saved.question());
        assertEquals(q.original().page(), saved.page().intValue());
        int expectedLine = extracted.evidence().stream().filter(e -> e.term().equals(q.original())).findFirst().orElseThrow().termLine().lineNumber();
        assertEquals(expectedLine, saved.lineNumber().intValue());
        assertEquals(q.explanation(), saved.explanation()); assertEquals(List.of("O", "X"), saved.options());
        assertEquals(1, saved.correctIndex());
        assertTrue(saved.explanation().contains(q.descriptionSource().page() + "페이지"));
    }

    @Test public void manualGenerationAndConsoleShowPresentedBAndUseNoPageLabel() {
        ManualTermSet manual = ManualTermSet.empty("").add("알파", "분리된 원본 자료를 보관하는 공간").add("베타", "변형된 다른 동작을 수행하는 과정");
        var workflow = new QuizWorkflow(new QuizRepository(), new QuizResultRepository(), Clock.systemUTC(), () -> new QuizGenerator(new Random(7)));
        var preview = workflow.generateManual(manual);
        assertEquals(1, preview.set().questions().size());
        SavedQuestion saved = preview.set().questions().getFirst();
        assertEquals(1, saved.correctIndex()); assertNull(saved.page()); assertNull(saved.lineNumber());
        assertEquals(manual.sourceLabel(), saved.sourcePdf());
        assertTrue(saved.explanation().contains("페이지 없음")); assertFalse(saved.explanation().contains("0페이지"));
        var q = new QuizGenerator(new Random(7)).generateOx(manual.terms()).getFirst();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new QuizGenerator(new Random(7)).runOx(manual.terms(), new Scanner("X\n"), new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("설명: " + q.presentedDescription())); assertTrue(output.contains("정답: X"));
        assertTrue(output.contains("해설: " + q.explanation())); assertTrue(output.contains("점수: 1/1"));
        assertFalse(output.contains("0페이지"));
    }
}

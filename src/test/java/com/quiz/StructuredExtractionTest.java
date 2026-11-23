package com.quiz;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import com.quiz.TermExtractor.*;

public class StructuredExtractionTest {
    private static final String DEFINITION = "청록빛 결정을 일정한 간격으로 옮기는 절차이다.";
    private Extraction extract(String... pages) { return new TermExtractor().extractDetailed(List.of(pages), 1, Mode.STRUCTURED); }

    @Test public void upperMarkersAndEntireBmpPuaRangeAreStructuredOnlyAndKeepSourceLines() {
        for (String prefix : List.of("", "• ", "▪ ", "● ", "■ ", "◦ ", "○ ", "· ", "\uE000 ", "\uE701 ", "\uF234 ", "\uF8FF ")) {
            String raw = prefix + "Lumera\n- " + DEFINITION;
            Result legacy = new TermExtractor().extract(List.of(raw));
            Extraction structured = extract(raw);
            assertTrue(legacy.terms().isEmpty()); assertEquals(1, structured.result().terms().size());
            assertEquals(new Term("Lumera", DEFINITION, 1), structured.result().terms().getFirst());
            Evidence e = structured.result().evidence().getFirst();
            assertEquals(SourceKind.TITLE_BULLET, e.sourceKind()); assertEquals(prefix + "Lumera", e.termLine().text());
            assertEquals(List.of(new SourceLine(1, 2, "- " + DEFINITION)), e.descriptionLines());
            assertEquals(structured, extract(raw));
        }
        assertEquals("\uF900Lumera", extract("\uF900Lumera\n- " + DEFINITION).result().terms().getFirst().term());
        assertEquals(1, extract("Lumera\n– " + DEFINITION).result().terms().size());
    }

    @Test public void invalidTitlesNumberMarkersExamplesAndMissingDashAreRejected() {
        for (String title : List.of("2) Lumera", "(2) Lumera", "① Lumera", "• 2) Lumera", "\uE123 2) Lumera", "12", "x".repeat(25),
                "조각을 옮긴다", "빛나는 부품임", "빛나는 부품됨", "빛나는 부품함", "부품이 있음", "부품이 없음", "- Lumera", "– Lumera"))
            assertEquals(title, 0, extract(title + "\n- " + DEFINITION).result().terms().size());
        assertEquals(1, extract("x".repeat(24) + "\n- " + DEFINITION).result().terms().size());
        assertEquals(1, extract("😀".repeat(12) + "\n- " + DEFINITION).result().terms().size());
        assertEquals(0, extract("😀".repeat(13) + "\n- " + DEFINITION).result().terms().size());
        assertEquals(0, extract("Lumera\n일반 설명만 존재한다.").result().terms().size());
        assertEquals(0, extract("Lumera\n\n- " + DEFINITION).result().terms().size());
        var examples = extract("Lumera\n- 예) 은빛 조각의 움직임\n예시에 이어진 부분\n- ex. amber pieces\n- e.g. violet pieces");
        assertEquals(0, examples.result().terms().size()); assertEquals(3, examples.stats().exampleBulletsExcluded());
        var mixed = extract("Lumera\n- 예) 은빛 조각의 움직임\n예시에 이어진 부분\n- " + DEFINITION);
        assertEquals(DEFINITION, mixed.result().terms().getFirst().description());
    }

    @Test public void unfinishedBulletContinuationCannotBecomeAHeading() {
        for (String marker : List.of("• ", "- ", "\uE789 ")) {
            var result = extract(marker + "은빛 조각을 천천히\n잘린 조각\n- " + DEFINITION);
            assertTrue(result.result().terms().isEmpty()); assertEquals(1, result.stats().continuationRejected());
        }
        var wrapped = extract("Lumera\n- 은빛 조각을 일정하게\n배치한다.\n독립된 후속 문장\n• Velori\n- 황금빛 조각을 보관하는 공간이다.");
        assertEquals(2, wrapped.result().terms().size());
        assertEquals("은빛 조각을 일정하게 배치한다.", wrapped.result().terms().getFirst().description());
        assertEquals(List.of(2, 3), wrapped.result().evidence().getFirst().descriptionLines().stream().map(SourceLine::lineNumber).toList());
        assertFalse(wrapped.result().terms().getFirst().description().contains("독립된"));
    }

    @Test public void consecutiveHeadingsAndPagesNeverJoinAcrossBoundaries() {
        var consecutive = extract("Lumera\n- " + DEFINITION + "\nVelori\n- 황금빛 결정을 보관하는 공간이다.");
        assertEquals(List.of("Lumera", "Velori"), consecutive.result().terms().stream().map(Term::term).toList());
        assertTrue(extract("Lumera", "- " + DEFINITION).result().terms().isEmpty());
        var offset = new TermExtractor().extractDetailed(List.of("Lumera\n- " + DEFINITION, "Velori\n- 황금빛 조각을 보관한다."), 8, Mode.STRUCTURED);
        assertEquals(List.of(8, 9), offset.result().terms().stream().map(Term::page).toList());
    }

    @Test public void legacyColonEvidenceHasPriorityAndOriginalTermsStayAsOrderedPrefix() {
        List<String> pages = List.of("Lumera\n- Velori: " + DEFINITION, "Solvex\n- 황금빛 조각을 보관하는 공간이다.");
        Result legacy = new TermExtractor().extract(pages);
        Extraction result = new TermExtractor().extractDetailed(pages, 1, Mode.STRUCTURED);
        assertEquals("Velori", legacy.terms().getFirst().term());
        assertEquals(legacy.terms(), result.result().terms().subList(0, legacy.terms().size()));
        assertSame(result.beforeMerge().terms().getFirst(), result.result().terms().getFirst());
        assertTrue(result.result().terms().stream().noneMatch(t -> t.term().equals("Lumera")));
        assertEquals(1, result.stats().collisionRejected());
        assertEquals(SourceKind.LEGACY, result.result().evidence().getFirst().sourceKind());
        List<String> absorbed = List.of("Lumera: " + DEFINITION + "\n• Velori\n- 황금빛 조각을 보관한다.");
        assertEquals(new TermExtractor().extract(absorbed).terms(), new TermExtractor().extract(absorbed, Mode.STRUCTURED).terms());
    }

    @Test public void onlyExactlyNormalizedPairsMergeAndEveryRepeatKeepsItsOwnPageAndLines() {
        var result = extract("• Lumera\n- " + DEFINITION, "\uE456  ＬＵＭＥＲＡ\n– 청록빛  결정을 일정한 간격으로 옮기는 절차이다.");
        assertEquals(2, result.beforeMerge().terms().size()); assertEquals(1, result.result().terms().size());
        Evidence first = result.result().evidence().getFirst(); assertEquals(1, first.term().page());
        assertEquals(new SourceLine(2, 1, "\uE456  ＬＵＭＥＲＡ"), first.repetitions().getFirst().title());
        assertEquals(2, first.repetitions().getFirst().descriptionLines().getFirst().lineNumber());
        assertEquals(1, result.stats().mergedOccurrences());
        QuizGenerator generator = new QuizGenerator(new Random(7));
        assertEquals(0, generator.screenedTerms(result.beforeMerge()).size()); assertEquals(1, generator.screenedTerms(result.result()).size());
        var conflict = extract("Lumera\n- " + DEFINITION, "Lumera\n- 황금빛 조각을 보관하는 공간이다.");
        assertEquals(2, conflict.result().terms().size()); assertEquals(0, conflict.stats().mergedOccurrences());
        assertTrue(generator.screenedTerms(conflict.result()).isEmpty());
        var near = extract("Lumera\n- " + DEFINITION, "Lumera\n- 청록빛 결정을 일정한 간격으로 옮기는 절차임.");
        assertEquals(2, near.result().terms().size()); assertEquals(0, near.stats().mergedOccurrences());
        var legacy = List.of("Lumera: " + DEFINITION, "Lumera: " + DEFINITION);
        Result mergedLegacy = new TermExtractor().extract(legacy, Mode.STRUCTURED);
        assertEquals(1, mergedLegacy.terms().size());
        assertEquals(1, mergedLegacy.evidence().getFirst().repetitions().size());
        assertEquals(1, generator.screenedTerms(mergedLegacy).size());
        assertEquals(2, new TermExtractor().extract(legacy, Mode.LEGACY).terms().size());
        assertTrue(generator.screenedTerms(new TermExtractor().extract(legacy, Mode.LEGACY)).isEmpty());
        var earlierStructure = extract("• Lumera\n- " + DEFINITION, "Lumera: " + DEFINITION);
        assertEquals(1, earlierStructure.result().terms().size());
        assertEquals(1, earlierStructure.result().terms().getFirst().page());
        assertEquals(SourceKind.TITLE_BULLET, earlierStructure.result().evidence().getFirst().sourceKind());
        assertEquals(2, earlierStructure.result().evidence().getFirst().repetitions().getFirst().title().page());
    }

    @Test public void unchangedScreenStillRejectsLongShortUrlAndSharedDescriptions() {
        for (String definition : List.of("짧음", "청록빛 결정".repeat(40), "청록빛 결정을 https://example.org 경로에서 보관하는 절차이다.")) {
            var result = extract("Lumera\n- " + definition);
            assertEquals(1, result.result().terms().size());
            assertTrue(new QuizGenerator(new Random(7)).screenedTerms(result.result()).isEmpty());
        }
        var shared = extract("Lumera\n- " + DEFINITION, "Velori\n- " + DEFINITION);
        assertEquals(2, shared.result().terms().size()); assertEquals(0, shared.stats().mergedOccurrences());
        assertTrue(new QuizGenerator(new Random(7)).screenedTerms(shared.result()).isEmpty());
        var matchingLegacy = extract("Lumera: " + DEFINITION, "• Lumera\n- " + DEFINITION);
        assertEquals(1, matchingLegacy.result().terms().size());
        assertEquals(1, matchingLegacy.result().evidence().getFirst().repetitions().size());
        assertEquals(SourceKind.LEGACY, matchingLegacy.result().evidence().getFirst().sourceKind());
    }
}

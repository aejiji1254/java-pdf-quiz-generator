package com.quiz;

import static com.quiz.NotebookFixtures.*;
import static org.junit.Assert.*;
import java.time.*;
import java.util.*;
import org.junit.Test;

public class WrongAnswerNotebookTest {
    @Test public void identicalParentLinksMergeButEachContentDifferenceBreaksOnlyThatEdge() {
        SavedQuestion a = question("a", null), b = linked("b", a), c = linked("c", b);
        SavedQuizSet sa = set("A", TIME, a), sb = set("B", TIME.plusSeconds(1), b), sc = set("C", TIME.plusSeconds(2), c);
        var same = aggregate(List.of(sa, sb, sc), result("a", sa, a, false, TIME), result("c", sc, c, true, TIME.plusSeconds(1)));
        assertEquals(1, same.groups().size()); assertEquals(2, same.groups().getFirst().attempts());
        for (SavedQuestion changed : List.of(b.edited(b.question() + " ", b.options(), 0, b.explanation()),
                b.edited(b.question(), List.of("보기 B", "정답 A", "보기 C", "보기 D"), 0, b.explanation()),
                b.edited(b.question(), b.options(), 1, b.explanation()))) {
            SavedQuestion d = linked("d", changed);
            SavedQuizSet changedSet = set("B", TIME, changed), sd = set("D", TIME, d);
            var snapshot = aggregate(List.of(sa, changedSet, sd), result("a", sa, a, false, TIME), result("d", sd, d, false, TIME));
            assertEquals(2, snapshot.groups().size());
            assertTrue(snapshot.groups().stream().anyMatch(g -> g.instances().size() == 2));
            assertTrue(snapshot.groups().stream().anyMatch(g -> g.instances().size() == 1));
        }
        SavedQuestion normalized = b.edited(b.question(), List.of("  정답   ａ ", "보기 b", "보기 c", "보기 d"), 0, "별도 해설");
        SavedQuizSet normalizedSet = set("N", TIME, normalized);
        assertEquals(1, aggregate(List.of(sa, normalizedSet), result("a", sa, a, false, TIME),
                result("n", normalizedSet, normalized, true, TIME)).groups().size());
        SavedQuestion front = linked("front", a);
        SavedQuestion back = linked("back", front).edited("다른 질문", front.options(), 0, "해설");
        SavedQuestion tail = linked("tail", back);
        SavedQuizSet four = set("four", TIME, a, front, back, tail);
        var brokenMiddle = aggregate(List.of(four), result("front", four, a, false, TIME), result("tail", four, tail, false, TIME));
        assertEquals(List.of(2, 2), brokenMiddle.groups().stream().map(g -> g.instances().size()).sorted().toList());
    }

    @Test public void twoThousandLinksCyclesSelfReferencesAndSiblingsAreIterative() {
        List<SavedQuizSet> sets = new ArrayList<>(); SavedQuestion previous = question("chain0", null);
        sets.add(set("chain0", TIME, previous));
        for (int i = 1; i <= 2000; i++) { previous = linked("chain" + i, previous); sets.add(set("chain" + i, TIME.plusSeconds(i), previous)); }
        var chain = aggregate(sets, result("chain", sets.getLast(), previous, false, TIME));
        assertEquals(1, chain.groups().size()); assertEquals(2001, chain.groups().getFirst().instances().size());
        SavedQuestion root = question("root", null), child1 = linked("child1", root), child2 = linked("child2", root);
        SavedQuestion cycleA = new SavedQuestion(id("ca"), root.type(), root.question(), root.options(), 0, "설명", SOURCE, 1, null, root.origin(), id("cb"));
        SavedQuestion cycleB = linked("cb", cycleA);
        SavedQuestion self = new SavedQuestion(id("self"), root.type(), root.question(), root.options(), 0, "설명", SOURCE, 1, null, root.origin(), id("self"));
        SavedQuizSet all = set("all", TIME, root, child1, child2, cycleA, cycleB, self);
        var snapshot = aggregate(List.of(all), result("root", all, root, false, TIME), result("cycle", all, cycleA, false, TIME), result("self", all, self, false, TIME));
        assertEquals(List.of(1, 2, 3), snapshot.groups().stream().map(g -> g.instances().size()).sorted().toList());
        var absent = set("absent", TIME, linked("orphan", question("missing", null)));
        assertEquals(1, aggregate(List.of(absent), result("orphan", absent, absent.questions().getFirst(), false, TIME)).groups().size());
    }

    @Test public void representativeAndQuizInstancePreferSetFilterThenNewestWithStableSetIdTie() {
        SavedQuestion root = question("root", null), child = linked("child", root), sibling = linked("sibling", root);
        SavedQuizSet old = set("old", TIME, root), newest = set("new", TIME.plusSeconds(10), child), tied = set("tied", TIME.plusSeconds(10), sibling);
        for (List<SavedQuizSet> input : List.of(List.of(old, newest, tied), List.of(tied, old, newest))) {
            var group = aggregate(input, result("wrong", old, root, false, TIME)).groups().getFirst();
            String expected = newest.id().compareTo(tied.id()) < 0 ? newest.id() : tied.id();
            assertEquals(expected, group.representative(null).set().id());
            assertEquals(expected, group.representative(id("not-present")).set().id());
            assertEquals(old.id(), group.representative(old.id()).set().id());
            assertEquals(root, group.representative(old.id()).question());
        }
    }

    @Test public void masteryUsesRecentFirstAttemptsAndResultIdTieNotRetrySuccess() {
        SavedQuestion q = question("a", null); SavedQuizSet set = set("A", TIME, q);
        List<QuizResult> history = new ArrayList<>(); history.add(result("wrong", set, q, false, TIME));
        for (int i = 1; i <= 3; i++) {
            history.add(result("right" + i, set, q, true, TIME.plusSeconds(i)));
            var group = aggregate(List.of(set), history.toArray(QuizResult[]::new)).groups().getFirst();
            assertEquals(i, group.streak()); assertEquals(i >= 3, group.mastered(3));
        }
        var mastered = aggregate(List.of(set), history.toArray(QuizResult[]::new));
        assertTrue(mastered.visible(null, null, false, 3, WrongAnswerNotebook.Order.RECENT_WRONG).isEmpty());
        assertEquals(1, mastered.visible(null, null, true, 3, WrongAnswerNotebook.Order.RECENT_WRONG).size());
        history.add(result("wrongAgain", set, q, false, TIME.plusSeconds(4)));
        assertEquals(0, aggregate(List.of(set), history.toArray(QuizResult[]::new)).groups().getFirst().streak());
        history.add(result("oneAfter", set, q, true, TIME.plusSeconds(5)));
        assertEquals(1, aggregate(List.of(set), history.toArray(QuizResult[]::new)).groups().getFirst().streak());
        assertTrue(aggregate(List.of(set), result("onlyRight", set, q, true, TIME)).groups().isEmpty());
        var session = new com.quiz.gui.QuizSession(set, set.questions(), null, Clock.fixed(TIME, ZoneOffset.UTC));
        session.submit("2"); session.submit("1");
        var retry = aggregate(List.of(set), session.finish()).groups().getFirst();
        assertEquals(1, retry.attempts()); assertEquals(1, retry.wrongCount()); assertEquals(0, retry.streak());
        var sameTimeA = result("tieA", set, q, true, TIME);
        var sameTimeB = result("tieB", set, q, false, TIME);
        var tied = aggregate(List.of(set), sameTimeB, sameTimeA).groups().getFirst();
        assertEquals(List.of(sameTimeA.id(), sameTimeB.id()).stream().sorted().toList(), tied.events().stream().map(WrongAnswerNotebook.Event::resultId).toList());
        assertEquals(sameTimeA.id().compareTo(sameTimeB.id()) > 0 ? 1 : 0, tied.streak());
        for (int n : new int[] {0, 11}) try { tied.mastered(n); fail("정복 범위 거부"); } catch (IllegalArgumentException expected) { }
    }

    @Test public void summaryOnlyNormalizesDisplayAndDoesNotBreakEmoji() {
        assertEquals("질문 하나 둘", WrongAnswerNotebook.summary("  질문\n하나\t  둘  "));
        String question = "😀".repeat(61);
        String summary = WrongAnswerNotebook.summary(question);
        assertEquals("😀".repeat(60) + "…", summary); assertEquals(61, summary.codePointCount(0, summary.length()));
        assertEquals("😀".repeat(60), WrongAnswerNotebook.summary("😀".repeat(60)));
        SavedQuestion a = question("a", null), b = linked("b", a).edited(a.question() + "\n", a.options(), 0, "설명");
        assertEquals(WrongAnswerNotebook.summary(a.question()), WrongAnswerNotebook.summary(b.question()));
        assertFalse(WrongAnswerNotebook.sameContent(a, b));
    }

    @Test public void recentWrongAndMostWrongHaveDifferentStableOrderAndFiltersUseMembership() {
        SavedQuestion a = question("a", null), b = question("b", null);
        SavedQuizSet sa = set("A", TIME, a), sb = set("B", TIME, b);
        var snapshot = aggregate(List.of(sb, sa), result("a1", sa, a, false, TIME), result("a2", sa, a, false, TIME.plusSeconds(1)),
                result("b1", sb, b, false, TIME.plusSeconds(2)), result("a3", sa, a, true, TIME.plusSeconds(3)));
        assertEquals(b.id(), snapshot.visible(null, null, true, 3, WrongAnswerNotebook.Order.RECENT_WRONG).getFirst().representative(null).question().id());
        assertEquals(a.id(), snapshot.visible(null, null, true, 3, WrongAnswerNotebook.Order.MOST_WRONG).getFirst().representative(null).question().id());
        assertEquals(TIME.plusSeconds(3), snapshot.groups().stream().filter(g -> g.representative(null).question().id().equals(a.id())).findFirst().orElseThrow().lastAnswered());
        assertEquals(1, snapshot.visible("과목 B", sb.id(), true, 3, WrongAnswerNotebook.Order.RECENT_WRONG).size());
        assertTrue(snapshot.visible("과목 B", sa.id(), true, 3, WrongAnswerNotebook.Order.RECENT_WRONG).isEmpty());
        QuizResult x = result("tie-sort-a", sa, a, false, TIME), y = result("tie-sort-b", sb, b, false, TIME);
        var tied = aggregate(List.of(sa, sb), y, x).visible(null, null, true, 3, WrongAnswerNotebook.Order.RECENT_WRONG);
        assertEquals(x.id().compareTo(y.id()) > 0 ? a.id() : b.id(), tied.getFirst().representative(null).question().id());
    }
}

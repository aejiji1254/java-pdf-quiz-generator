package com.quiz;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** 세션당 첫 답만 집계한다. 최신 첫 시도부터 연속 N회 정답이면 정복이다. */
public final class WrongAnswerNotebook {
    public enum Order { RECENT_WRONG, MOST_WRONG }
    public record Instance(SavedQuizSet set, SavedQuestion question) { }
    public record Event(String resultId, AnswerRecord answer) { }
    private static final Comparator<Event> TIME = Comparator.comparing((Event e) -> e.answer().answeredAt())
            .thenComparing(Event::resultId).thenComparing(e -> e.answer().questionId());
    private static final Comparator<Instance> REPRESENTATIVE = Comparator.comparing((Instance i) -> i.set().createdAt()).reversed()
            .thenComparing(i -> i.set().id()).thenComparing(i -> i.question().id());

    public record Group(String id, List<Instance> instances, List<Event> events) {
        public Group { instances = List.copyOf(instances); events = List.copyOf(events); }
        public long wrongCount() { return events.stream().filter(e -> !e.answer().correct()).count(); }
        public int attempts() { return events.size(); }
        public Instant lastAnswered() { return events.getLast().answer().answeredAt(); }
        public Event lastWrong() { return events.stream().filter(e -> !e.answer().correct()).max(TIME).orElseThrow(); }
        public int streak() {
            int count = 0;
            for (int i = events.size() - 1; i >= 0 && events.get(i).answer().correct(); i--) count++;
            return count;
        }
        public boolean mastered(int n) { checkThreshold(n); return streak() >= n; }
        public Instance representative(String setId) {
            boolean selected = setId != null && instances.stream().anyMatch(i -> i.set().id().equals(setId));
            return instances.stream().filter(i -> !selected || i.set().id().equals(setId)).min(REPRESENTATIVE).orElseThrow();
        }
        public boolean matches(String subject, String setId) {
            return instances.stream().anyMatch(i -> (subject == null || i.set().subject().equals(subject))
                    && (setId == null || i.set().id().equals(setId)));
        }
    }

    public record Snapshot(List<Group> groups, int excludedResults, List<String> reasons,
            int unreadableSets, List<String> setReasons) {
        public Snapshot { groups = List.copyOf(groups); reasons = List.copyOf(reasons); setReasons = List.copyOf(setReasons); }
        public List<Group> visible(String subject, String setId, boolean includeMastered, int n, Order order) {
            checkThreshold(n);
            Comparator<Group> recent = Comparator.comparing(Group::lastWrong, TIME).reversed().thenComparing(Group::id);
            Comparator<Group> comparator = order == Order.MOST_WRONG
                    ? Comparator.comparingLong(Group::wrongCount).reversed().thenComparing(recent) : recent;
            return groups.stream().filter(g -> g.matches(subject, setId) && (includeMastered || !g.mastered(n)))
                    .sorted(comparator).toList();
        }
    }

    public Snapshot load(QuizRepository quizzes, QuizResultRepository results) throws IOException {
        QuizRepository.Listing catalog = quizzes.list();
        Map<String, SavedQuizSet> sets = new LinkedHashMap<>();
        for (var entry : catalog.entries()) sets.put(entry.set().id(), entry.set());
        QuizResultRepository.Listing history = results.list(sets);
        return aggregate(new ArrayList<>(sets.values()), history, catalog.unreadableCount(), catalog.reasons());
    }

    public Snapshot aggregate(List<SavedQuizSet> sets, QuizResultRepository.Listing history, int unreadableSets, List<String> reasons) {
        List<Instance> nodes = new ArrayList<>();
        Map<String, List<Integer>> byId = new LinkedHashMap<>();
        Map<String, Integer> byInstance = new LinkedHashMap<>();
        for (SavedQuizSet set : sets.stream().sorted(Comparator.comparing(SavedQuizSet::id)).toList())
            for (SavedQuestion question : set.questions()) {
                int index = nodes.size(); nodes.add(new Instance(set, question));
                byId.computeIfAbsent(question.id(), ignored -> new ArrayList<>()).add(index);
                byInstance.put(set.id() + "/" + question.id(), index);
            }
        int[] parent = new int[nodes.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        // 재귀 없이 각 부모-자식 연결을 검사하며 불일치한 연결만 끊는다.
        for (int child = 0; child < nodes.size(); child++) {
            SavedQuestion question = nodes.get(child).question();
            for (int same : byId.get(question.id())) if (sameContent(question, nodes.get(same).question())) union(parent, child, same);
            List<Integer> ancestors = byId.get(question.derivedFromQuestionId());
            if (ancestors != null) for (int ancestor : ancestors)
                if (sameContent(question, nodes.get(ancestor).question())) union(parent, child, ancestor);
        }
        Map<Integer, List<Instance>> instances = new LinkedHashMap<>();
        Map<Integer, List<Event>> events = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) instances.computeIfAbsent(find(parent, i), ignored -> new ArrayList<>()).add(nodes.get(i));
        for (var valid : history.entries()) {
            valid.result().validateAgainst(valid.set());
            for (AnswerRecord answer : valid.result().answers()) {
                Integer index = byInstance.get(valid.set().id() + "/" + answer.questionId());
                if (index == null) throw new IllegalArgumentException("집계 세트 목록에 없는 문항입니다.");
                events.computeIfAbsent(find(parent, index), ignored -> new ArrayList<>()).add(new Event(valid.result().id(), answer));
            }
        }
        List<Group> groups = new ArrayList<>();
        for (var entry : events.entrySet()) {
            List<Event> ordered = entry.getValue(); ordered.sort(TIME);
            if (ordered.stream().anyMatch(e -> !e.answer().correct())) {
                List<Instance> members = instances.get(entry.getKey());
                String id = members.stream().map(i -> i.set().id() + "/" + i.question().id()).min(String::compareTo).orElseThrow();
                groups.add(new Group(id, members, ordered));
            }
        }
        return new Snapshot(groups, history.excludedCount(), history.reasons(), unreadableSets, reasons);
    }

    public static boolean sameContent(SavedQuestion a, SavedQuestion b) {
        return a.question().equals(b.question()) && a.correctIndex() == b.correctIndex()
                && a.options().stream().map(SavedQuestion::optionKey).toList()
                        .equals(b.options().stream().map(SavedQuestion::optionKey).toList());
    }
    public static String summary(String question) {
        String text = question.replaceAll("(?U)\\s+", " ").strip();
        int size = text.codePointCount(0, text.length());
        return size > 60 ? text.substring(0, text.offsetByCodePoints(0, 60)) + "…" : text;
    }
    private static void checkThreshold(int n) { if (n < 1 || n > 10) throw new IllegalArgumentException("정복 기준은 1~10입니다."); }
    private static int find(int[] parent, int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    }
    private static void union(int[] parent, int a, int b) {
        int x = find(parent, a), y = find(parent, b);
        if (x != y) parent[Math.max(x, y)] = Math.min(x, y);
    }
}

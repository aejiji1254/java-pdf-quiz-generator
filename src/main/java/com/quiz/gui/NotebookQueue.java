package com.quiz.gui;

import com.quiz.*;
import java.util.*;

/** 시작마다 새 큐를 만들고 한 세트만 꺼낸다. 중간 종료/메인 이동 시 남은 큐는 버린다. */
final class NotebookQueue {
    record Batch(SavedQuizSet set, List<SavedQuestion> questions) { Batch { questions = List.copyOf(questions); } }
    private final Deque<Batch> pending = new ArrayDeque<>();
    void start(List<WrongAnswerNotebook.Group> groups, String setId) {
        clear();
        Map<String, SavedQuizSet> sets = new LinkedHashMap<>();
        Map<String, List<SavedQuestion>> questions = new LinkedHashMap<>();
        Set<String> selected = new HashSet<>();
        for (var group : groups) if (selected.add(group.id())) {
            var instance = group.representative(setId);
            sets.putIfAbsent(instance.set().id(), instance.set());
            questions.computeIfAbsent(instance.set().id(), ignored -> new ArrayList<>()).add(instance.question());
        }
        for (var entry : sets.entrySet()) pending.addLast(new Batch(entry.getValue(), questions.get(entry.getKey())));
    }
    Batch next() { return pending.pollFirst(); }
    int remaining() { return pending.size(); }
    void clear() { pending.clear(); }
}

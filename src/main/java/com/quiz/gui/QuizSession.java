package com.quiz.gui;

import com.quiz.AnswerRecord;
import com.quiz.QuizResult;
import com.quiz.SavedQuestion;
import com.quiz.SavedQuizSet;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 하나의 GUI 풀이 상태. 채점은 기존 AnswerRecord.grade를 사용한다. */
public final class QuizSession {
    private final SavedQuizSet set;
    private final List<SavedQuestion> questions;
    private final String reviewOfResultId;
    private final Clock clock;
    private final Instant startedAt;
    private final List<AnswerRecord> answers = new ArrayList<>();
    private final Deque<SavedQuestion> wrongStack = new ArrayDeque<>();
    private final Set<String> wrongIds = new HashSet<>();
    private final Map<String, Integer> attemptCounts = new LinkedHashMap<>();
    private final Set<String> solvedIds = new HashSet<>();
    private int index;
    private QuizResult finished;

    public QuizSession(SavedQuizSet set, List<SavedQuestion> questions,
            String reviewOfResultId, Clock clock) {
        if (questions.stream().anyMatch(question -> !set.questions().contains(question)))
            throw new IllegalArgumentException("문제가 원래 세트에 없습니다.");
        this.set = set;
        this.questions = List.copyOf(questions);
        this.reviewOfResultId = reviewOfResultId;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    public SavedQuizSet set() { return set; }
    public List<SavedQuestion> questions() { return questions; }
    public int index() { return index; }
    public int total() { return questions.size(); }
    public List<AnswerRecord> answers() { return List.copyOf(answers); }
    /** 가장 최근 오답부터 반환한다. 현재 세션에서는 각 문항을 한 번만 푼다. */
    public List<SavedQuestion> wrongQuestions() {
        Deque<SavedQuestion> copy = new ArrayDeque<>(wrongStack);
        List<SavedQuestion> wrong = new ArrayList<>();
        while (!copy.isEmpty()) wrong.add(copy.pop());
        return List.copyOf(wrong);
    }
    public boolean submitted() { return solved(); }
    public boolean solved() { return !isComplete() && solvedIds.contains(current().id()); }
    public int attempts() { return isComplete() ? 0 : attemptCounts.getOrDefault(current().id(), 0); }
    public int retries() { return Math.max(0, attempts() - 1); }
    public Map<String, Integer> attemptCounts() { return Map.copyOf(attemptCounts); }
    public boolean isFinished() { return finished != null; }
    public boolean isComplete() { return index >= questions.size(); }

    public SavedQuestion current() {
        if (isComplete() || isFinished()) throw new IllegalStateException("진행 중인 문제가 없습니다.");
        return questions.get(index);
    }

    public AnswerRecord submit(String selected) {
        if (isFinished() || solved()) throw new IllegalStateException("이미 해결했거나 종료된 문제입니다.");
        SavedQuestion question = current();
        AnswerRecord answer = AnswerRecord.grade(question, selected, clock.instant());
        if (!attemptCounts.containsKey(question.id())) answers.add(answer);
        attemptCounts.merge(question.id(), 1, Integer::sum);
        if (!answer.correct() && wrongIds.add(question.id())) wrongStack.push(question);
        if (answer.correct()) solvedIds.add(question.id());
        return answer;
    }

    public void next() {
        if (!solved() || isFinished()) throw new IllegalStateException("먼저 정답을 맞히세요.");
        index++;
    }

    public QuizResult finish() {
        if (finished == null) {
            finished = new QuizResult(UUID.randomUUID().toString(), set.id(), set.sourcePdf(),
                    reviewOfResultId, questions.size(), startedAt, answers,
                    attemptCounts, solvedIds);
        }
        return finished;
    }
}

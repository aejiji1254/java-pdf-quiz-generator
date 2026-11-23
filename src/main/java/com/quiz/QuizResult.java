package com.quiz;

import java.time.Instant;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 한 번의 풀이 결과. 복습은 이전 결과를 바꾸지 않는 새 결과다. */
public record QuizResult(String id, String quizSetId, String sourcePdf, String reviewOfResultId, int totalQuestions,
        Instant createdAt, List<AnswerRecord> answers, Map<String, Integer> attemptCounts,
        Set<String> solvedQuestionIds) {
    public QuizResult(String id, String quizSetId, String sourcePdf, String reviewOfResultId,
            int totalQuestions, Instant createdAt, List<AnswerRecord> answers) {
        this(id, quizSetId, sourcePdf, reviewOfResultId, totalQuestions, createdAt, answers,
                Map.of(), Set.of());
    }

    public QuizResult {
        if (!uuid(id) || !uuid(quizSetId) || sourcePdf == null || sourcePdf.isBlank()
                || reviewOfResultId != null && !uuid(reviewOfResultId)
                || totalQuestions < 0 || createdAt == null || answers == null) {
            throw new IllegalArgumentException("학습 결과의 필수 정보가 올바르지 않습니다.");
        }
        answers = List.copyOf(answers);
        if (attemptCounts == null || solvedQuestionIds == null)
            throw new IllegalArgumentException("재시도 상태가 올바르지 않습니다.");
        attemptCounts = Map.copyOf(attemptCounts);
        solvedQuestionIds = Set.copyOf(solvedQuestionIds);
        Map<String, Integer> checkedAttempts = attemptCounts;
        Set<String> checkedSolved = solvedQuestionIds;
        if (answers.size() > totalQuestions)
            throw new IllegalArgumentException("답안 수가 출제된 문제 수를 넘었습니다.");
        Set<String> ids = new HashSet<>();
        for (AnswerRecord answer : answers) {
            if (!ids.add(answer.questionId())) {
                throw new IllegalArgumentException("한 결과에 같은 문항의 답이 중복되었습니다.");
            }
        }
        if (!ids.containsAll(checkedAttempts.keySet()) || !ids.containsAll(checkedSolved)
                || !checkedAttempts.isEmpty() && !checkedAttempts.keySet().equals(ids)
                || !checkedAttempts.keySet().containsAll(checkedSolved)
                || checkedAttempts.values().stream().anyMatch(count -> count == null || count < 1)
                || answers.stream().anyMatch(answer -> answer.correct()
                        && !checkedAttempts.isEmpty() && !checkedSolved.contains(answer.questionId()))
                || answers.stream().anyMatch(answer -> !answer.correct()
                        && checkedSolved.contains(answer.questionId())
                        && checkedAttempts.get(answer.questionId()) < 2))
            throw new IllegalArgumentException("재시도 상태가 답안 기록과 일치하지 않습니다.");
    }

    private static boolean uuid(String text) {
        try {
            return text != null && UUID.fromString(text).toString().equalsIgnoreCase(text);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public void validateAgainst(SavedQuizSet set) {
        if (!quizSetId.equals(set.id()) || !sourcePdf.equals(set.sourcePdf())) throw new IllegalArgumentException("문제 세트 ID가 다릅니다.");
        if (totalQuestions > set.questions().size())
            throw new IllegalArgumentException("출제된 문제 수가 문제 세트보다 많습니다.");
        Map<String, SavedQuestion> questions = set.questions().stream()
                .collect(Collectors.toMap(SavedQuestion::id, Function.identity()));
        for (AnswerRecord answer : answers) {
            SavedQuestion question = questions.get(answer.questionId());
            if (question == null) throw new IllegalArgumentException("기록에 없는 문제 ID가 있습니다.");
            answer.validateAgainst(question);
        }
    }

    public long correctCount() {
        return answers.stream().filter(AnswerRecord::correct).count();
    }

    public long wrongCount() {
        return answers.size() - correctCount();
    }

    public long retriedSolvedCount() {
        return answers.stream().filter(answer -> !answer.correct()
                && solvedQuestionIds.contains(answer.questionId())).count();
    }

    public long unresolvedAttemptedCount() {
        return wrongCount() - retriedSolvedCount();
    }

    public List<SavedQuestion> wrongQuestions(SavedQuizSet set) {
        validateAgainst(set);
        Set<String> wrongIds = answers.stream().filter(a -> !a.correct())
                .map(AnswerRecord::questionId).collect(Collectors.toSet());
        return set.questions().stream().filter(q -> wrongIds.contains(q.id())).toList();
    }

    public List<SavedQuestion> wrongQuestionsRecentFirst(SavedQuizSet set) {
        validateAgainst(set);
        Map<String, SavedQuestion> questions = set.questions().stream()
                .collect(Collectors.toMap(SavedQuestion::id, Function.identity()));
        Deque<SavedQuestion> stack = new ArrayDeque<>();
        for (AnswerRecord answer : answers) {
            if (!answer.correct()) stack.push(questions.get(answer.questionId()));
        }
        List<SavedQuestion> wrong = new ArrayList<>();
        while (!stack.isEmpty()) wrong.add(stack.pop());
        return List.copyOf(wrong);
    }
}

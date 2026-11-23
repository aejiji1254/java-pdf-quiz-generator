package com.quiz;

import java.util.List;
import java.util.UUID;
import java.text.Normalizer;
import java.util.Locale;

/** 편집은 검증된 새 문항을 만들고, 풀이와 복습은 저장된 보기 순서와 정답을 유지한다. */
public record SavedQuestion(String id, Type type, String question, List<String> options,
        int correctIndex, String explanation, String sourcePdf, Integer page, Integer lineNumber,
        Origin origin, String derivedFromQuestionId) {
    public enum Type { OX, MULTIPLE_CHOICE }
    public enum Origin { GENERATED, MANUAL, UNKNOWN }

    public SavedQuestion(String id, Type type, String question, List<String> options,
            int correctIndex, String explanation, String sourcePdf, Integer page, Integer lineNumber) {
        this(id, type, question, options, correctIndex, explanation, sourcePdf, page, lineNumber,
                Origin.UNKNOWN, null);
    }

    public SavedQuestion {
        if (origin == null || derivedFromQuestionId != null
                && !UUID.fromString(derivedFromQuestionId).toString().equalsIgnoreCase(derivedFromQuestionId))
            throw new IllegalArgumentException("문항 출처 연결 정보가 올바르지 않습니다.");
        if (id == null || !UUID.fromString(id).toString().equalsIgnoreCase(id)
                || type == null || question == null || question.isBlank()
                || explanation == null || explanation.isBlank()
                || sourcePdf == null || sourcePdf.isBlank() || options == null) {
            throw new IllegalArgumentException("문제의 필수 정보가 없거나 올바르지 않습니다.");
        }
        options = List.copyOf(options);
        if (options.stream().anyMatch(String::isBlank)
                || options.stream().map(SavedQuestion::optionKey).distinct().count() != options.size()
                || correctIndex < 0 || correctIndex >= options.size()) {
            throw new IllegalArgumentException("보기 또는 정답 위치가 올바르지 않습니다.");
        }
        if (type == Type.OX && !options.equals(List.of("O", "X"))
                || type == Type.MULTIPLE_CHOICE && options.size() != 4) {
            throw new IllegalArgumentException("문제 유형과 보기 개수가 맞지 않습니다.");
        }
        if (page != null && page < 1 || lineNumber != null && (lineNumber < 1 || page == null)) {
            throw new IllegalArgumentException("출처 페이지 또는 줄 번호가 올바르지 않습니다.");
        }
    }

    /** 화면 편집과 JSON 불러오기에 동일한 중복 기준을 적용한다. */
    public static String optionKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("(?U)\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    public SavedQuestion edited(String question, List<String> options, int correctIndex,
            String explanation) {
        return new SavedQuestion(id, type, question, options, correctIndex, explanation,
                sourcePdf, page, lineNumber, origin, derivedFromQuestionId);
    }
}

package com.quiz;

/** GUI 전용 확장 옵션. null O/X 개수는 기존 전체 생성 결과를 그대로 사용한다. */
public record GenerationOptions(Integer oxCount, int multipleChoiceCount, Direction direction) {
    public enum Direction {
        FORWARD("정방향만"), REVERSE("역방향만"), MIXED("섞기");
        private final String label;
        Direction(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public static final int MAX_COUNT = 500;
    public static GenerationOptions defaults() { return new GenerationOptions(null, 30, Direction.MIXED); }
    public GenerationOptions {
        if (oxCount != null && (oxCount < 0 || oxCount > MAX_COUNT)
                || multipleChoiceCount < 0 || multipleChoiceCount > MAX_COUNT || direction == null)
            throw new IllegalArgumentException("문항 수는 0~500, 방향은 필수입니다.");
    }
}

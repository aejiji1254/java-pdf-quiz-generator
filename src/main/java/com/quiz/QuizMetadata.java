package com.quiz;

/** 출처는 파일 경로가 아닌 표시 문자열일 수도 있으므로 Path로 해석하지 않는다. */
public final class QuizMetadata {
    private QuizMetadata() { }

    public static String fileName(String source) {
        if (source == null) return "";
        return source.substring(Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\')) + 1);
    }

    public static String portableSource(String source) {
        String marker = "수동 입력 (원문 대조 필요: ";
        if (source.startsWith(marker)) {
            String pdf = source.substring(marker.length());
            if (pdf.endsWith(")")) pdf = pdf.substring(0, pdf.length() - 1);
            return marker + portableFileName(pdf) + ")";
        }
        if (isManualSource(source)) return source;
        return portableFileName(source);
    }

    private static String portableFileName(String source) {
        String name = fileName(source).replaceFirst("^[A-Za-z]:", "");
        return name.isBlank() ? "(출처 없음)" : name;
    }

    public static String exportFileName(String title) {
        String safe = title.replaceAll("[<>:\"/\\\\|?*\\p{Cc}\\p{Cf}]", "").strip().replaceAll("[. ]+$", "");
        if (safe.isEmpty()) safe = "퀴즈 세트";
        if (safe.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) safe = "_" + safe;
        return safe + ".json";
    }

    public static String withSuffix(String title, String suffix) {
        int limit = 60 - suffix.codePointCount(0, suffix.length());
        String prefix = title.substring(0, title.offsetByCodePoints(0, Math.min(limit, title.codePointCount(0, title.length())))).strip();
        return prefix + suffix;
    }

    public static String defaultTitle(String source) {
        if (isManualSource(source)) {
            String marker = "수동 입력 (원문 대조 필요: ";
            if (!source.startsWith(marker)) return "수동 입력";
            String pdf = source.substring(marker.length());
            if (pdf.endsWith(")")) pdf = pdf.substring(0, pdf.length() - 1);
            return fallback("수동 입력 - " + withoutExtension(fileName(pdf)));
        }
        return fallback(withoutExtension(fileName(source)));
    }

    public static String legacyTitle(String source) {
        return isManualSource(source) ? defaultTitle(source) : fallback(fileName(source));
    }

    private static boolean isManualSource(String source) {
        return source != null && (source.equals("수동 입력") || source.equals("수동 입력 (PDF 미지정)")
                || source.startsWith("수동 입력 (원문 대조 필요: "));
    }

    private static String withoutExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static String fallback(String title) {
        String safe = (title == null ? "" : title).replaceAll("[\\p{Cc}\\p{Cf}]", "").strip();
        if (safe.isEmpty()) safe = "(제목 없음)";
        return safe.substring(0, safe.offsetByCodePoints(0, Math.min(60, safe.codePointCount(0, safe.length())))).strip();
    }

    public static String title(String value) { return validate(value, 1, 60, "이름"); }
    public static String subject(String value) { return validate(value, 0, 30, "과목"); }

    private static String validate(String value, int min, int max, String label) {
        if (value == null || value.codePoints().anyMatch(c -> Character.getType(c) == Character.CONTROL
                || Character.getType(c) == Character.FORMAT))
            throw new IllegalArgumentException(label + "에는 제어·서식 문자를 사용할 수 없습니다.");
        String trimmed = value.strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < min || length > max) throw new IllegalArgumentException(label + "은 " + min + "~" + max + "자여야 합니다.");
        return trimmed;
    }
}

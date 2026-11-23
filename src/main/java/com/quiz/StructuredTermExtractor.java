package com.quiz;

import java.util.*;
import java.util.regex.Pattern;
import com.quiz.TermExtractor.*;

/** STRUCTURED 전용 원문 연결. LEGACY 처리/마커와 생성기 screen은 바꾸지 않는다. */
final class StructuredTermExtractor {
    // screen의 String.length() 24 제한과 동일하게 UTF-16 24자로 제한한다(이모지는 두 자리).
    static final int MAX_TITLE = 24;
    private static final Pattern DASH = Pattern.compile("^[-–]\\s+\\S.*");
    private static final Pattern ENDING = Pattern.compile(".*(?:다|됨|임|함|있음|없음)[.!?。！？]*[’”'\")]*$");
    private static final Pattern EXAMPLE = Pattern.compile("(?iu)^(?:예\\)|ex\\.|e\\.g\\.).*");
    private record Pair(String title, String description) { }

    Extraction extract(List<String> pages, int firstPage, Result legacy) {
        Set<String> legacyLines = new HashSet<>();
        for (Evidence e : legacy.evidence()) {
            if (e.termLine() != null) legacyLines.add(key(e.termLine()));
            for (SourceLine l : e.descriptionLines()) legacyLines.add(key(l));
            for (SourceLine l : e.interruptions()) legacyLines.add(key(l));
        }
        List<Evidence> raw = new ArrayList<>();
        int shapes = 0, guarded = 0, collisions = 0, examples = 0, empty = 0;
        for (int p = 0; p < pages.size(); p++) {
            String[] lines = pages.get(p).split("\\R", -1);
            boolean[] continuation = continuations(lines);
            for (int i = 0; i + 1 < lines.length; i++) {
                String title = title(lines[i]);
                if (title == null || !dash(lines[i + 1])) continue;
                shapes++;
                if (continuation[i]) { guarded++; continue; }
                SourceLine heading = new SourceLine(firstPage + p, i + 1, lines[i]);
                if (legacyLines.contains(key(heading))) { collisions++; continue; }
                List<SourceLine> sources = new ArrayList<>(); List<String> parts = new ArrayList<>();
                boolean open = false, skipExample = false;
                for (int j = i + 1; j < lines.length; j++) {
                    String line = lines[j].strip();
                    SourceLine source = new SourceLine(firstPage + p, j + 1, lines[j]);
                    if (line.isEmpty() || line.matches("[0-9]+") || line.contains("\t")) break;
                    if (legacyLines.contains(key(source)) || line.contains(":")) { collisions++; break; }
                    if (dash(line)) {
                        String body = line.substring(1).strip();
                        if (EXAMPLE.matcher(body).matches()) { examples++; skipExample = true; open = !ends(body); continue; }
                        sources.add(source); parts.add(body); open = !ends(body); skipExample = false;
                    } else {
                        if (marked(line) || !open) break;
                        // 미완결 줄 뒤의 무표지 조각을 새 제목으로 오인하지 않고, 새 구조 경계에서는 연결도 멈춘다.
                        if (j + 1 < lines.length && dash(lines[j + 1]) && title(line) != null) break;
                        if (!skipExample) { sources.add(source); parts.add(line); }
                        open = !ends(line);
                    }
                }
                if (parts.isEmpty()) { empty++; continue; }
                Term term = new Term(title, String.join(" ", parts), heading.page());
                raw.add(new Evidence(term, heading, sources, List.of(), SourceKind.TITLE_BULLET, List.of()));
            }
        }
        List<Term> beforeTerms = new ArrayList<>(legacy.terms()); List<Evidence> beforeEvidence = new ArrayList<>(legacy.evidence());
        for (Evidence e : raw) { beforeTerms.add(e.term()); beforeEvidence.add(e); }
        Result before = new Result(beforeTerms, legacy.warnings(), beforeEvidence, legacy.candidateCount() + raw.size());
        List<Term> terms = new ArrayList<>(); List<Evidence> evidence = new ArrayList<>();
        Map<Pair, Integer> first = new LinkedHashMap<>();
        int merged = 0, added = 0;
        for (Evidence e : beforeEvidence) {
            Integer index = first.get(pair(e.term()));
            if (index == null) {
                first.put(pair(e.term()), evidence.size()); evidence.add(e); terms.add(e.term());
                if (e.sourceKind() == SourceKind.TITLE_BULLET) added++;
            } else {
                Evidence original = evidence.get(index);
                if (earlier(e.termLine(), original.termLine())) {
                    Evidence later = original; original = e; e = later;
                    terms.set(index, original.term());
                    added++;
                }
                List<Occurrence> repeats = new ArrayList<>(original.repetitions());
                repeats.add(new Occurrence(e.termLine(), e.descriptionLines()));
                repeats.addAll(e.repetitions());
                repeats.sort(Comparator.comparingInt((Occurrence o) -> o.title().page()).thenComparingInt(o -> o.title().lineNumber()));
                evidence.set(index, new Evidence(original.term(), original.termLine(), original.descriptionLines(), original.interruptions(),
                        original.sourceKind(), repeats)); merged++;
            }
        }
        // STRUCTURED만 정확히 같은 쌍을 통합한다. 기존 쌍의 내용은 제거하지 않고 반복 출처로 보존한다.
        return new Extraction(new Result(terms, legacy.warnings(), evidence, legacy.candidateCount() + raw.size()), before,
                new StructuredStats(shapes, guarded, collisions, examples, empty, raw.size(), merged, added));
    }

    private static Pair pair(Term t) { return new Pair(SavedQuestion.optionKey(t.term()), SavedQuestion.optionKey(t.description())); }
    private static boolean earlier(SourceLine a, SourceLine b) {
        return a.page() < b.page() || a.page() == b.page() && a.lineNumber() < b.lineNumber();
    }
    private static String key(SourceLine l) { return l.page() + ":" + l.lineNumber(); }
    static boolean dash(String text) { return DASH.matcher(text.strip()).matches(); }
    static boolean ends(String text) { return ENDING.matcher(text.strip()).matches(); }
    private static boolean pua(char c) { return c >= '\uE000' && c <= '\uF8FF'; }
    static boolean marked(String raw) {
        String text = raw.strip();
        return !text.isEmpty() && (pua(text.charAt(0)) || !TermExtractor.leadingMarker(text).isEmpty() || text.startsWith("–"));
    }
    static String title(String raw) {
        String text = raw.strip();
        if (text.isEmpty() || text.startsWith("-") || text.startsWith("–")) return null;
        while (!text.isEmpty()) {
            if (pua(text.charAt(0))) { text = text.substring(1).strip(); continue; }
            String marker = TermExtractor.leadingMarker(text);
            if (marker.isEmpty()) break;
            if (marker.indexOf('-') >= 0 || marker.codePoints().anyMatch(c -> Character.isDigit(c) || Character.getType(c) == Character.OTHER_NUMBER)) return null;
            text = text.substring(marker.length()).strip();
            if (dash(text)) return null;
        }
        if (text.isEmpty() || text.length() > MAX_TITLE || ends(text) || text.matches("[0-9]+")
                || text.contains(":") || text.contains("\t") || text.contains("___") || EXAMPLE.matcher(text).matches()) return null;
        return text;
    }
    private static boolean[] continuations(String[] lines) {
        boolean[] continuation = new boolean[lines.length]; boolean open = false;
        for (int i = 0; i < lines.length; i++) {
            String text = lines[i].strip();
            if (text.isEmpty()) { open = false; continue; }
            if (marked(text)) { open = !ends(text); }
            else { continuation[i] = open; open = open && !ends(text); }
        }
        return continuation;
    }
}

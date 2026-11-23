package com.quiz;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class TermExtractor {
    public enum Mode { LEGACY, STRUCTURED }
    public enum SourceKind { LEGACY, TITLE_BULLET }
    private static final Pattern MARKER = Pattern.compile(
            "^(?:(?:[•▪●■◦○·-]\\s*)|\\d+[.)]\\s*|\\(\\d+\\)\\s*|[①-⑳]\\s*)+");
    public record Warning(int page, String line, String reason) { }
    public record SourceLine(int page, int lineNumber, String text) { }
    public record Occurrence(SourceLine title, List<SourceLine> descriptionLines) {
        public Occurrence { descriptionLines = List.copyOf(descriptionLines); }
    }
    public record Evidence(Term term, SourceLine termLine, List<SourceLine> descriptionLines,
            List<SourceLine> interruptions, SourceKind sourceKind, List<Occurrence> repetitions) {
        public Evidence(Term term, SourceLine termLine, List<SourceLine> descriptionLines, List<SourceLine> interruptions) {
            this(term, termLine, descriptionLines, interruptions, SourceKind.LEGACY, List.of());
        }
        public Evidence {
            descriptionLines = List.copyOf(descriptionLines);
            interruptions = List.copyOf(interruptions);
            repetitions = List.copyOf(repetitions);
            java.util.Objects.requireNonNull(sourceKind);
        }
        // LEGACY의 기존 record 문자열/hash를 보존하여 고정 추출 기준을 유지한다.
        @Override public String toString() {
            String base = "Evidence[term=" + term + ", termLine=" + termLine + ", descriptionLines=" + descriptionLines
                    + ", interruptions=" + interruptions;
            return sourceKind == SourceKind.LEGACY && repetitions.isEmpty() ? base + "]"
                    : base + ", sourceKind=" + sourceKind + ", repetitions=" + repetitions + "]";
        }
        @Override public int hashCode() {
            int value = java.util.Objects.hashCode(term);
            value = 31 * value + java.util.Objects.hashCode(termLine);
            value = 31 * value + descriptionLines.hashCode();
            value = 31 * value + interruptions.hashCode();
            return sourceKind == SourceKind.LEGACY && repetitions.isEmpty() ? value
                    : 31 * (31 * value + sourceKind.ordinal()) + repetitions.hashCode();
        }
    }

    public record StructuredStats(int shapeCandidates, int continuationRejected, int collisionRejected,
            int exampleBulletsExcluded, int emptyDefinitions, int occurrences, int mergedOccurrences, int addedTerms) {
        static StructuredStats empty() { return new StructuredStats(0, 0, 0, 0, 0, 0, 0, 0); }
    }
    public record Extraction(Result result, Result beforeMerge, StructuredStats stats) { }

    public Result extract(List<String> pages, int firstPage, Mode mode) {
        return extractDetailed(pages, firstPage, mode).result();
    }
    public Result extract(List<String> pages, Mode mode) { return extract(pages, 1, mode); }
    public Extraction extractDetailed(List<String> pages, int firstPage, Mode mode) {
        java.util.Objects.requireNonNull(mode);
        Result legacy = extract(pages, firstPage);
        return mode == Mode.LEGACY ? new Extraction(legacy, legacy, StructuredStats.empty())
                : new StructuredTermExtractor().extract(pages, firstPage, legacy);
    }
    static String leadingMarker(String text) {
        var match = MARKER.matcher(text); return match.find() ? match.group() : "";
    }

    public record Result(List<Term> terms, List<Warning> warnings, List<Evidence> evidence,
            int candidateCount) {
        public Result {
            terms = List.copyOf(terms);
            warnings = List.copyOf(warnings);
            evidence = List.copyOf(evidence);
            if (candidateCount < terms.size()) throw new IllegalArgumentException("용어 후보 수가 유효 쌍보다 적습니다.");
        }

        public Result(List<Term> terms, List<Warning> warnings) {
            this(terms, warnings, List.of(), terms.size());
        }

        public Result(List<Term> terms, List<Warning> warnings, List<Evidence> evidence) {
            this(terms, warnings, evidence, terms.size());
        }
    }

    /** pages의 첫 항목은 1페이지다. 빈 페이지도 목록에 유지해야 한다. */
    public Result extract(List<String> pages) {
        return extract(pages, 1);
    }

    public Result extract(List<String> pages, int firstPage) {
        if (firstPage < 1) throw new IllegalArgumentException("첫 페이지는 1 이상이어야 합니다.");
        List<List<SourceLine>> linesByPage = new ArrayList<>();
        for (int p = 0; p < pages.size(); p++) {
            List<SourceLine> lines = new ArrayList<>();
            String[] originals = pages.get(p).split("\\R", -1);
            for (int i = 0; i < originals.length; i++) {
                if (!originals[i].isBlank()) lines.add(new SourceLine(firstPage + p, i + 1, originals[i]));
            }
            linesByPage.add(lines);
        }
        Map<String, Integer> occurrences = new HashMap<>();
        for (List<SourceLine> lines : linesByPage) {
            Set<String> seen = new HashSet<>();
            for (SourceLine line : lines) {
                seen.add(line.text().strip());
            }
            for (String line : seen) {
                occurrences.merge(line, 1, Integer::sum);
            }
        }

        List<Term> terms = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        int candidateCount = 0;
        for (int p = 0; p < linesByPage.size(); p++) {
            List<SourceLine> lines = linesByPage.get(p);
            String term = null;
            SourceLine termSource = null;
            boolean example = false;
            StringBuilder description = new StringBuilder();
            List<SourceLine> descriptionSources = new ArrayList<>();
            List<SourceLine> interruptions = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                SourceLine source = lines.get(i);
                String original = source.text();
                String line = original.strip();
                String reason = exclusionReason(line, occurrences);
                // 설명 중의 일반 문장 뒤 ':'는 같은 용어의 추가 설명으로 해석한다.
                boolean separated = i > 0 && source.lineNumber() - lines.get(i - 1).lineNumber() > 1;
                boolean termLine = ((term == null && !example) || MARKER.matcher(line).find()
                        || (separated && !example))
                        && !line.startsWith(":") && i + 1 < lines.size()
                        && lines.get(i + 1).text().strip().startsWith(":");
                int separator = line.indexOf(':');
                boolean inlineTerm = !example
                        && separator > 0 && separator <= 40
                        && line.indexOf(':', separator + 1) < 0
                        && !line.substring(separator + 1).isBlank();
                boolean endsItem = reason != null && !line.contains("___");
                if (endsItem || (reason == null && (termLine || inlineTerm))) {
                    finish(terms, warnings, evidence, term, termSource, description,
                            descriptionSources, interruptions, firstPage + p);
                    term = null;
                    termSource = null;
                    description.setLength(0);
                    descriptionSources.clear();
                    interruptions.clear();
                }
                if (reason != null) {
                    warnings.add(new Warning(firstPage + p, original, reason));
                    if (term != null && line.contains("___")) interruptions.add(source);
                    if (line.startsWith("ex.")) example = true;
                } else if (termLine || inlineTerm) {
                    example = false;
                    String cleaned = MARKER.matcher(inlineTerm
                            ? line.substring(0, separator) : line).replaceFirst("").strip();
                    if (cleaned.isEmpty()) {
                        warnings.add(new Warning(firstPage + p, original, "용어가 비어 있습니다"));
                    } else {
                        candidateCount++;
                        term = cleaned;
                        termSource = source;
                        if (inlineTerm) {
                            description.append(line.substring(separator + 1).strip());
                            descriptionSources.add(source);
                        }
                    }
                } else if (example) {
                    warnings.add(new Warning(firstPage + p, original, "예시 구간의 줄"));
                } else if (term != null) {
                    String part = line.startsWith(":") ? line.substring(1).strip() : line;
                    if (!part.isEmpty()) {
                        if (description.length() > 0) {
                            description.append(' ');
                        }
                        description.append(part);
                        descriptionSources.add(source);
                    }
                } else {
                    warnings.add(new Warning(firstPage + p, original, "연결할 용어가 없는 줄"));
                }
            }
            finish(terms, warnings, evidence, term, termSource, description,
                    descriptionSources, interruptions, firstPage + p);
        }
        return new Result(terms, warnings, evidence, candidateCount);
    }

    private static String exclusionReason(String line, Map<String, Integer> occurrences) {
        if (line.chars().filter(c -> c == ':').count() > 1 || line.contains("\t"))
            return "표 또는 여러 열의 관계가 불명확한 줄";
        if (line.contains("___")) return "밑줄 빈칸이 있는 줄";
        if (line.startsWith("ex.")) return "예시 줄";
        if (line.matches("[0-9]+")) return "숫자만 있는 줄";
        if (line.codePointCount(0, line.length()) <= 20
                && occurrences.getOrDefault(line, 0) >= 3) return "여러 페이지에 반복되는 줄";
        return null;
    }

    private static void finish(List<Term> terms, List<Warning> warnings, List<Evidence> evidence,
            String term, SourceLine termSource, StringBuilder description,
            List<SourceLine> descriptionSources, List<SourceLine> interruptions, int page) {
        if (term == null) return;
        if (description.length() == 0) {
            warnings.add(new Warning(page, term, "유효한 설명이 없는 용어"));
        } else {
            Term extracted = new Term(term, description.toString(), page);
            terms.add(extracted);
            evidence.add(new Evidence(extracted, termSource, descriptionSources, interruptions));
        }
    }
}

package com.quiz;

import java.nio.file.*;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** 일회성 분석 전용. 제품 규칙은 호출만 하며 docs 쓰기는 명시적 시스템 프로퍼티로 제한한다. */
public class ExtractionYieldAnalysis {
    private static final Path DOCUMENT = Path.of("docs/extraction-yield-analysis.md");
    private static final Pattern BULLET = Pattern.compile("^(?:[•▪●■◦○·-]\\s*|\\d+[.)]\\s*|\\(\\d+\\)\\s*|[①-⑳]\\s*)");
    private static final Pattern BLANK = Pattern.compile("_{3,}");
    private static final Pattern ENDING = Pattern.compile(".*(?:이다|한다|된다|였다|있다|없다|임|됨|함|있음|없음)[.!?。！？]*[’”'\")]*$");
    private static final Pattern EXAMPLE = Pattern.compile("(?iu)(?:^|\\s)(?:ex\\.|e\\.g\\.)|(?:^|\\s)(?:그러나|하지만|반면|반대로|대조적으로|이에\\s+비해)(?:\\s|[,.:])|\\bbut\\b");
    private enum Kind {
        A("(a) 밑줄 빈칸"), B("(b) 제목 아래 목록"), C("(c) 짧은 제목·헤더 후보"), D("(d) 명시적 예시·대비"),
        E("(e) 표 형태"), F("(f) 서술형 종결 후보"), G("(g) 기타");
        final String label; Kind(String label) { this.label = label; }
    }
    private record Line(int page, int number, String text) { String key() { return page + ":" + number; } }
    private record Range(int first, int last, int lines, int candidates, int pairs, int warnings,
            int eligibleOx, int eligibleMc, int o, int x, int forward, int reverse) { }
    private record Gap(int total, int insufficient, int noMatch, int ambiguous, int unique, int explicitTerm) { }
    private record Analysis(Path pdf, String hash, List<Line> lines, List<Line> pool, Map<Kind, List<Line>> types,
            List<Range> ranges, Map<String, Integer> warnings, int unmappedWarnings, Gap gaps) { }

    @Test public void mechanicalRulesAcceptGroundedMatchesAndRejectInsufficientOrAmbiguousEvidence() {
        Line blank = new Line(1, 1, "학습의 결과는 ___에 의해 행동이 변화한다");
        Line filled = new Line(2, 1, "학습의 결과는 강화에 의해 행동이 변화한다");
        Gap found = gaps(List.of(blank), List.of(blank, filled), Set.of("강화"));
        assertEquals(1, found.unique()); assertEquals(1, found.explicitTerm());
        assertEquals(0, gaps(List.of(blank), List.of(blank, new Line(2, 1, "강화는 다른 줄에도 있다")), Set.of("강화")).unique());
        assertEquals(1, gaps(List.of(blank), List.of(blank, filled, new Line(3, 1, "학습의 결과는 경험에 의해 행동이 변화한다")), Set.of("강화", "경험")).ambiguous());
        Line shortContext = new Line(1, 2, "___이다");
        assertEquals(1, gaps(List.of(shortContext), List.of(shortContext, filled), Set.of()).insufficient());
        assertEquals(Kind.A, classify(new Line(1, 1, "• ___이다"), Map.of(), Set.of("1:1"), Set.of()));
        assertEquals(Kind.E, classify(new Line(1, 1, "열1:값1 열2:값2"), Map.of(), Set.of(), Set.of()));
        assertEquals(Kind.G, classify(new Line(1, 1, "• 문맥 불명 항목"), Map.of(), Set.of(), Set.of()));
        assertEquals("😀".repeat(100) + "…", shortText("😀".repeat(101)));
        assertEquals("😀".repeat(100), shortText("😀".repeat(100)));
    }

    @Test public void analyzeWithoutChangingProductRulesAndWriteOnlyWithExplicitFlag() throws Exception {
        byte[] before = Files.exists(DOCUMENT) ? Files.readAllBytes(DOCUMENT) : null;
        var beforeTime = Files.exists(DOCUMENT) ? Files.getLastModifiedTime(DOCUMENT) : null;
        List<Analysis> analyses = new ArrayList<>();
        List<Path> pdfs = new ArrayList<>();
        if (Files.isRegularFile(Path.of("sample.pdf"))) pdfs.add(Path.of("sample.pdf"));
        if (Files.isDirectory(Path.of("samples"))) try (var paths = Files.walk(Path.of("samples"))) {
            pdfs.addAll(paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                    && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")).sorted().toList());
        }
        assertFalse("분석 PDF가 없습니다.", pdfs.isEmpty());
        for (Path pdf : pdfs) analyses.add(analyze(pdf));
        String document = render(analyses);
        assertFalse("문서에 개인 절대 경로를 기록하지 않는다", document.contains(Path.of("").toAbsolutePath().toString()));
        if (Boolean.getBoolean("quiz.writeDocs")) Files.writeString(DOCUMENT, document);
        else if (before == null) assertFalse(Files.exists(DOCUMENT));
        else { assertArrayEquals(before, Files.readAllBytes(DOCUMENT)); assertEquals(beforeTime, Files.getLastModifiedTime(DOCUMENT)); }
        for (Analysis a : analyses) System.out.println("YIELD " + name(a) + " lines=" + a.lines().size() + " pool=" + a.pool().size()
                + " warnings=" + a.warnings().values().stream().mapToInt(Integer::intValue).sum() + " gaps=" + a.gaps());
    }

    private Analysis analyze(Path pdf) throws Exception {
        PdfReader reader = new PdfReader(); int pages = reader.pageCount(pdf);
        List<Line> all = new ArrayList<>(); List<Range> ranges = new ArrayList<>();
        Set<String> connected = new LinkedHashSet<>(), warned = new LinkedHashSet<>();
        Map<String, Integer> reasons = new TreeMap<>(); int unmapped = 0;
        Set<String> explicitTerms = new LinkedHashSet<>();
        for (int first = 1; first <= pages; first += 50) {
            int last = Math.min(first + 49, pages);
            List<String> text = reader.readRange(pdf, first, last);
            List<Line> raw = lines(text, first); all.addAll(raw);
            TermExtractor.Result extracted = new TermExtractor().extract(text, first);
            for (var evidence : extracted.evidence()) {
                if (evidence.termLine() != null) connected.add(evidence.termLine().page() + ":" + evidence.termLine().lineNumber());
                for (var line : evidence.descriptionLines()) connected.add(line.page() + ":" + line.lineNumber());
                explicitTerms.add(key(evidence.term().term()));
            }
            for (var warning : extracted.warnings()) {
                reasons.merge(warning.reason(), 1, Integer::sum);
                List<Line> matches = raw.stream().filter(line -> line.page() == warning.page() && line.text().equals(warning.line()) && !warned.contains(line.key())).toList();
                if (matches.isEmpty()) matches = raw.stream().filter(line -> line.page() == warning.page() && !warned.contains(line.key())
                        && BULLET.matcher(line.text().strip()).replaceFirst("").strip().equals(warning.line().strip())).toList();
                if (matches.isEmpty()) unmapped++; else warned.add(matches.getFirst().key());
            }
            QuizGenerator generator = new QuizGenerator(new Random(7));
            var mc = generator.generateMultipleChoice(extracted, 30, GenerationOptions.Direction.MIXED);
            var ox = generator.generateOxDetailed(extracted);
            int o = (int) ox.questions().stream().filter(QuizGenerator.OxQuestion::answer).count();
            int reverse = (int) mc.questions().stream().filter(QuizGenerator::reverse).count();
            ranges.add(new Range(first, last, raw.size(), extracted.candidateCount(), extracted.terms().size(), extracted.warnings().size(),
                    ox.candidateCount(), mc.candidateCount(), o, ox.questions().size() - o, mc.questions().size() - reverse, reverse));
        }
        List<Line> pool = all.stream().filter(line -> !connected.contains(line.key()) || warned.contains(line.key())).toList();
        Map<Kind, List<Line>> types = new EnumMap<>(Kind.class); for (Kind k : Kind.values()) types.put(k, new ArrayList<>());
        Map<String, Set<Integer>> repeats = new LinkedHashMap<>();
        for (Line line : all) repeats.computeIfAbsent(line.text().strip(), ignored -> new LinkedHashSet<>()).add(line.page());
        Set<String> listItems = new LinkedHashSet<>(), listHeaders = new LinkedHashSet<>();
        for (int i = 0; i < all.size(); i++) {
            Line line = all.get(i);
            if (!shortHeader(line.text())) continue;
            int end = i + 1;
            while (end < all.size() && all.get(end).page() == line.page() && bullet(all.get(end).text())) end++;
            if (end - i - 1 >= 2) { listHeaders.add(line.key()); for (int j = i + 1; j < end; j++) listItems.add(all.get(j).key()); }
        }
        for (Line line : pool) types.get(classify(line, repeats, listItems, listHeaders)).add(line);
        assertEquals(pool.size(), types.values().stream().mapToInt(List::size).sum());
        assertEquals(reasons.values().stream().mapToInt(Integer::intValue).sum(), ranges.stream().mapToInt(Range::warnings).sum());
        return new Analysis(pdf, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pdf))),
                all, pool, types, ranges, reasons, unmapped, gaps(types.get(Kind.A), all, explicitTerms));
    }

    private static List<Line> lines(List<String> pages, int first) {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            String[] raw = pages.get(i).split("\\R", -1);
            for (int j = 0; j < raw.length; j++) if (!raw[j].isBlank()) lines.add(new Line(first + i, j + 1, raw[j]));
        }
        return lines;
    }
    private static boolean bullet(String text) { return BULLET.matcher(text.strip()).find(); }
    private static boolean sentence(String text) { return text.strip().codePointCount(0, text.strip().length()) >= 12 && ENDING.matcher(text.strip()).matches(); }
    private static boolean shortHeader(String raw) {
        String text = raw.strip();
        return text.codePointCount(0, text.length()) <= 20 && !text.matches("[0-9]+") && !text.contains(":") && !text.contains("\t")
                && !BLANK.matcher(text).find() && !bullet(text) && !sentence(text) && !EXAMPLE.matcher(text).find();
    }
    private static Kind classify(Line line, Map<String, Set<Integer>> repeats, Set<String> listItems, Set<String> listHeaders) {
        String text = line.text().strip();
        if (BLANK.matcher(text).find()) return Kind.A;
        if (text.contains("\t") || text.chars().filter(c -> c == ':').count() > 1) return Kind.E;
        if (EXAMPLE.matcher(text).find()) return Kind.D;
        if (listItems.contains(line.key())) return Kind.B;
        if (shortHeader(text) && (listHeaders.contains(line.key()) || repeats.get(text).size() >= 3)) return Kind.C;
        if (sentence(text)) return Kind.F;
        return Kind.G;
    }

    private static Gap gaps(List<Line> blanks, List<Line> all, Set<String> explicitTerms) {
        int insufficient = 0, noMatch = 0, ambiguous = 0, unique = 0, explicit = 0;
        for (Line blank : blanks) {
            String text = key(blank.text()); Matcher gap = BLANK.matcher(text);
            gap.find(); int start = gap.start(), end = gap.end();
            String before = text.substring(0, start).strip(), after = text.substring(end).strip();
            if (gap.find() || before.codePointCount(0, before.length()) < 4 || after.codePointCount(0, after.length()) < 4) { insufficient++; continue; }
            Pattern filled = Pattern.compile(Pattern.quote(before) + "(.{1,40}?)" + Pattern.quote(after));
            Set<String> answers = new LinkedHashSet<>();
            for (Line line : all) if (!line.key().equals(blank.key())) {
                String other = key(line.text()); if (BLANK.matcher(other).find()) continue;
                Matcher match = filled.matcher(other);
                while (match.find()) { String answer = match.group(1).strip();
                    if (!answer.isBlank() && !answer.matches("[\\p{P}\\s]+")) answers.add(answer); }
            }
            if (answers.isEmpty()) noMatch++;
            else if (answers.size() > 1) ambiguous++;
            else { unique++; if (explicitTerms.contains(answers.iterator().next())) explicit++; }
        }
        assertEquals(blanks.size(), insufficient + noMatch + ambiguous + unique);
        return new Gap(blanks.size(), insufficient, noMatch, ambiguous, unique, explicit);
    }

    private static String render(List<Analysis> analyses) {
        Map<String,Analysis> unique = new LinkedHashMap<>(); for (Analysis a : analyses) unique.putIfAbsent(a.hash(),a);
        StringBuilder out = new StringBuilder("# PDF 추출 수율 분석\n\n분석일: 2026-11-22. 분석만 수행했다. 제품 규칙·저장 형식·기존 테스트는 변경하지 않았다.\n\n")
                .append("실행 환경: Java ").append(System.getProperty("java.version")).append(", ").append(System.getProperty("os.name")).append("; 기존 PDFBox 3.0.3.\n\n")
                .append("## 재현 방법과 집계 단위\n\n```powershell\nmvn test -Dtest=ExtractionYieldAnalysis -Dquiz.writeDocs=true\nmvn test -Dtest=ExtractionYieldAnalysis\nmvn clean test\n```\n\n")
                .append("도구는 `src/test/java/com/quiz/ExtractionYieldAnalysis.java` 한 개다. 일반 테스트 검색 대상이 아니며 명시 실행한다. ")
                .append("`quiz.writeDocs=true`일 때만 이 문서를 쓴다. 프로퍼티가 없으면 메모리에서 분석하고 기존 문서의 바이트·수정 시각 불변을 검사한다.\n\n")
                .append("PdfReader의 기존 정렬·간격 설정과 readRange를 그대로 사용한다. 최대 50페이지씩 별도 추출·생성하므로 55페이지 파일은 두 세트의 합계다. ")
                .append("각 범위에서 seed 7로 MC→OX 순서, GUI 기본 섞기/MC 30/OX 가능한 전부를 사용한다. 최종 수는 이 조건의 실측이며 모든 가능한 문항의 최대 수가 아니다.\n\n")
                .append("전체 줄은 빈 줄을 제외한 PDFTextStripper 물리 줄이다. 페이지·행은 PDF 기준 페이지 번호와 해당 추출 텍스트의 원래 행 번호다. ")
                .append("warnings는 경고 이벤트 수이며 곧바로 제외 줄 수로 간주하지 않는다. 동일 페이지의 동일 원문을 순서대로 대응하고, 정리된 용어 경고는 불릿 제거 후 대응한다. ")
                .append("분류 대상은 유효 Evidence의 용어/설명 줄에 연결되지 않았거나 경고에 대응하는 고유 물리 줄이다. ")
                .append("생성기에서 추가로 탈락한 유효 쌍의 연결 줄은 이 분류에 다시 넣지 않는다. 추출기 유효 쌍과 최종 문항 사이의 손실은 깔때기에서 별도로 보인다.\n\n")
                .append("TermExtractor는 같은 줄의 콜론과 다음 줄의 ':'뿐 아니라 열린 용어 뒤 일반 줄도 설명으로 누적한다. 따라서 경고가 없는 줄이 반드시 좋은 문제 재료라는 뜻은 아니다.\n\n")
                .append("## 입력 해시와 처리 범위\n\n| PDF 파일명 | SHA-256 | 페이지 범위 |\n| --- | --- | --- |\n");
        for (Analysis a : analyses) out.append("| ").append(name(a)).append(" | `").append(a.hash()).append("` | ").append(rangeLabel(a)).append(" |\n");
        out.append("\n같은 해시의 파일은 같은 자료 사본이다. 아래 파일별 합계는 사본도 각각 세며, 전체 비율은 중복 포함/해시 중복 제외를 구분한다.\n\n")
                .append("## 파일별 깔때기\n\n| PDF | 전체 줄 | 용어 후보 | 유효 쌍 | 경고 이벤트 | 분류 대상 줄 | O/X (O/X) | 객관식 (정/역) | 최종 합계 |\n| --- | ---: | ---: | ---: | ---: | ---: | --- | --- | ---: |\n");
        for (Analysis a : analyses) funnel(out, name(a), a.ranges(), a.pool().size());
        out.append("\n범위별 실측(적합 후보는 생성기 screen 및 유형별 길이 제한 이후의 수):\n\n| PDF / 범위 | 전체 줄 | 후보 | 유효 쌍 | 경고 | OX 적합 후보 | MC 적합 후보 | O/X | MC |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (Analysis a : analyses) for (Range r : a.ranges()) out.append("| ").append(name(a)).append(" / ").append(r.first()).append("–").append(r.last())
                .append(" | ").append(r.lines()).append(" | ").append(r.candidates()).append(" | ").append(r.pairs()).append(" | ")
                .append(r.warnings()).append(" | ").append(r.eligibleOx()).append(" | ").append(r.eligibleMc()).append(" | ")
                .append(r.o()+r.x()).append(" | ").append(r.forward()+r.reverse()).append(" |\n");
        out.append("\n유효 쌍이 곧 출제 가능한 쌍은 아니다. 생성기는 용어/설명 중복·문장 형태·URL/코드형 문자열 등을 추가 검사하고 OX는 설명 100 code point 제한도 적용한다. ")
                .append("MC는 안전한 후보가 4개 이상이고 앵커별 안전한 오답 3개가 있어야 한다. 숫자에서 확인되는 추가 손실과 warnings의 미연결 손실을 혼동하지 않는다.\n");
        out.append("\n## warnings 사유별 집계\n\n| PDF | 사유 | 경고 건수 |\n| --- | --- | ---: |\n");
        for (Analysis a : analyses) for (var reason : a.warnings().entrySet()) out.append("| ").append(name(a)).append(" | ").append(reason.getKey()).append(" | ").append(reason.getValue()).append(" |\n");
        out.append("\n경고 위치 대응 실패: ").append(analyses.stream().mapToInt(Analysis::unmappedWarnings).sum()).append("건. 미대응 경고도 사유별 집계에는 포함한다.\n\n")
                .append("## 기계적 분류 규칙\n\n분류 우선순위는 a→e→d→b→c→f→g이며 한 줄은 한 유형에만 센다. 예를 들어 밑줄이 있는 불릿은 a다. ")
                .append("명시한 형식 조건만 사용하고 의미·핵심 구는 판단하지 않는다. 조건을 충족하지 않는 짧은 줄/불릿/문장 조각은 기타다.\n\n")
                .append("- a: 연속 ASCII 밑줄 3개 이상.\n- e: ASCII 콜론 2개 이상 또는 탭.\n")
                .append("- d: 줄 시작/공백 뒤 ex. 또는 e.g.(대소문자 무관), 독립 but, 또는 명시적 대비 표지 그러나/하지만/반면/반대로/대조적으로/이에 비해. ")
                .append("한국어 표지 뒤에는 공백·쉼표·점·콜론이 있어야 한다.\n")
                .append("- b: 같은 페이지에서 아래 c의 짧은 헤더 형식 줄 다음에 연속된 불릿/번호 줄이 2개 이상일 때 그 항목들. ")
                .append("빈 줄은 무시하되 줄 사이의 일반 문장이 있으면 목록 연결을 추측하지 않는다. 표지는 •▪●■◦○·- / 숫자.) / (숫자) / ①–⑳.\n")
                .append("- c: 20 code point 이하, 숫자만 아님, 콜론·탭·밑줄·불릿·예시 표지·아래 f 종결형이 없고, ")
                .append("목록 2개 이상을 바로 이끌거나 같은 원문이 3페이지 이상 반복되는 줄. 제목의 의미를 확인한 것이 아니라 보수적 형식 후보다.\n")
                .append("- f: 12 code point 이상이며 이다/한다/된다/였다/있다/없다/임/됨/함/있음/없음과 선택적 문장부호·닫는 따옴표/괄호로 끝나는 줄. ")
                .append("형태적 종결 후보이며 문맥상 완결성까지 보장하지 않는다.\n- g: 위 형식으로 확정되지 않는 나머지.\n\n")
                .append("| PDF | a | b | c | d | e | f | g | 합계 |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (Analysis a : analyses) { out.append("| ").append(name(a)); for (Kind k : Kind.values()) out.append(" | ").append(a.types().get(k).size()); out.append(" | ").append(a.pool().size()).append(" |\n"); }
        out.append("\n## 대표 원문: 유형별 최대 5줄\n\n각 PDF를 순서대로 번갈아 선택한다. 같은 원문은 우선 중복을 피하고 부족하면 다른 위치의 반복 줄로 채운다. ")
                .append("5줄 미만이면 있는 만큼만 표시한다. 100 code point를 넘는 원문은 **앞 100자 + …**로 표시하며, 분류·검색은 잘리지 않은 원문을 사용한다.\n");
        for (Kind k : Kind.values()) {
            out.append("\n### ").append(k.label).append("\n\n| PDF 파일명 | 페이지 | 행 | 대표 원문 |\n| --- | ---: | ---: | --- |\n");
            List<Map.Entry<Analysis,Line>> selected = examples(analyses, k);
            for (var e : selected) out.append("| ").append(name(e.getKey())).append(" | ").append(e.getValue().page()).append(" | ").append(e.getValue().number())
                    .append(" | ").append(cell(shortText(e.getValue().text()))).append(" |\n");
            if (selected.isEmpty()) out.append("| — | — | — | 해당 유형 없음 |\n");
        }
        out.append("\n## 빈칸의 원문 정답 확인 가능성\n\nNFKC+소문자+공백 통합 후 같은 PDF의 다른 완성형 물리 줄에서 앞·뒤 문맥을 모두 문자 그대로 찾는다. ")
                .append("밑줄 구간은 1개, 양쪽 문맥 각각 4 code point 이상, 사이의 답 후보는 1–40자여야 한다. 밑줄이 남은 참조 줄은 사용하지 않는다. ")
                .append("답 후보가 하나뿐인 경우와, 그 후보가 추출기의 유효한 명시 용어로 다른 줄에 존재하는 경우를 구분한다. ")
                .append("다른 곳에 용어가 단순히 등장한다는 사실만으로는 해당 빈칸의 정답이라고 세지 않는다. 줄을 합치거나 동의어·상식으로 추론하지 않는다.\n\n")
                .append("| PDF | a 전체 | 복수 빈칸/문맥 부족 | 완성 문맥 일치 없음 | 복수 답 후보 | 단일 답 후보 | 그중 명시 용어 재등장 |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (Analysis a : analyses) { Gap g=a.gaps(); out.append("| ").append(name(a)).append(" | ").append(g.total()).append(" | ").append(g.insufficient()).append(" | ")
                .append(g.noMatch()).append(" | ").append(g.ambiguous()).append(" | ").append(g.unique()).append(" | ").append(g.explicitTerm()).append(" |\n"); }
        int total = analyses.stream().mapToInt(a -> a.gaps().total()).sum(), confirmed = analyses.stream().mapToInt(a -> a.gaps().unique()).sum();
        out.append("\n파일별 합계(사본 포함) ").append(total).append("줄 중 단일 완성 문맥 답 후보 ").append(confirmed).append("줄. ");
        int distinctTotal = unique.values().stream().mapToInt(a -> a.gaps().total()).sum();
        int distinctFound = unique.values().stream().mapToInt(a -> a.gaps().unique()).sum();
        out.append("**SHA-256 중복 제외 ").append(distinctTotal).append("줄 중 단일 답 후보 ").append(distinctFound).append("줄, 확인되지 않은 줄 ")
                .append(distinctTotal-distinctFound).append("줄(").append(percent(distinctTotal-distinctFound,distinctTotal)).append(")**. ");
        if (distinctTotal > 0 && (distinctTotal-distinctFound)*2 > distinctTotal) out.append("따라서 현재의 엄격한 원문 검색만으로는 **사용자가 정답을 입력해야 하는 반자동 방식이 필요**하다. ");
        else out.append("이 숫자만으로 자동 출제를 확정하지 않고 후보별 원문 검토가 필요하다. ");
        out.append("이는 PDF에 정답이 실제로 없다는 증명이 아니다. 줄바꿈·표·이미지·표현 차이 때문에 검색이 실패한 경우도 포함한다.\n\n")
                .append("## 서술형 후보 수와 비율\n\n핵심 용어처럼 보이는 구를 찾거나 의미를 평가하지 않았다. 분류 우선순위상 a–e로 먼저 분류된 종결형 줄은 f에 다시 세지 않는다.\n\n")
                .append("| PDF/집계 | f 후보 줄 | 전체 비공백 줄 대비 | 분류 대상 줄 대비 |\n| --- | ---: | ---: | ---: |\n");
        for (Analysis a : analyses) ratio(out, name(a), List.of(a));
        ratio(out,"전체 파일(사본 포함)",analyses);
        ratio(out,"SHA-256 중복 제외",new ArrayList<>(unique.values()));
        out.append("\n## 가능한 문제 형태: 제안만, 구현 없음\n\n다음 수는 파일별 분류 대상의 **정규화 원문 중복 제거 수 U**를 사용한 한 줄당 최대 1문항의 보수적 후보 상한이다. ")
                .append("자료 사본은 SHA-256으로 제외했다. 적합성 검토 전 예상 수율은 0–U이며 실제 생성 수 보장이 아니다. ")
                .append("사용자 확인 없이 새로 추가할 수 있다고 검증한 문항 수는 모든 유형에서 0이다. 새 X 문장이나 오답 3개를 임의로 만들지 않는다. ")
                .append("아래 파일명은 향후 변경을 검토할 곳이지 이번에 변경한 파일이 아니다.\n\n")
                .append("| 유형/안전한 형식 | 필요한 사용자 입력 | 수 추정(줄 수 기반) | 오류 혼입 위험 | 향후 변경 후보 파일 |\n| --- | --- | --- | --- | --- |\n");
        String[] proposals = {
            "빈칸 4지선다|정답·원문 근거·오답 3개와 유일 정답 확인|복수 빈칸/문맥 누락, 단순 재등장이 정답으로 오인됨|gui/QuestionEditorPanel.java, gui/QuizWorkflow.java, gui/QuizMakerFrame.java",
            "목록 항목을 사용자가 검증한 질문으로 작성|상위 제목과 항목 관계·정답·원문 근거·보기 확인|계층/열 순서와 독립 항목을 잘못 연결|TermExtractor.java, gui/QuestionEditorPanel.java, gui/QuizWorkflow.java",
            "제목에 원문 설명을 수동 연결한 기존 O/MC|해당 제목의 정의·페이지 근거, MC이면 오답 3개|헤더/쪽번호를 개념으로 오인; 제목만으로 설명 없음|ManualTermSet.java, gui/QuizMakerFrame.java, gui/QuizWorkflow.java",
            "예시·대비의 문맥을 확인한 기존 O/MC|대상 용어·조건·원문 전체 관계·정답/보기 확인|조건·부정·예시 일부만 인용하면 의미 변경|TermExtractor.java, gui/QuestionEditorPanel.java, gui/QuizWorkflow.java",
            "사용자가 열 대응을 확인한 용어-설명 O/MC|열/행 대응·정답 관계·PDF 페이지 확인|PDF 텍스트 순서가 실제 표의 대응과 다름|TermExtractor.java, gui/QuizMakerFrame.java, gui/QuizWorkflow.java",
            "원문에서 사용자가 지정한 구간을 빈칸 MC로 작성|빈칸 구간 직접 선택·문맥 확인·근거 있는 오답 3개|문장 조각·종결어미 동형성·선택 구간에 따라 모호함|gui/QuestionEditorPanel.java, gui/QuizWorkflow.java, gui/QuizMakerFrame.java",
            "수동 검토 후 기존 문제 편집/작성|명확한 용어·설명 또는 완전한 질문·보기·정답·출처|형식 불명확으로 가장 높음; 확인 못 하면 제외|ManualTermSet.java, gui/QuestionEditorPanel.java, gui/QuizMakerFrame.java"
        };
        int n=0;
        for(Kind k:Kind.values()) {
            Set<String> originals=new LinkedHashSet<>(); int raw=0;
            for(Analysis a:unique.values()) { raw+=a.types().get(k).size(); for(Line l:a.types().get(k)) originals.add(key(l.text())); }
            String[] p=proposals[n++].split("\\|",-1);
            out.append("| ").append(k.label).append(": ").append(p[0]).append(" | ").append(p[1]).append(" | 자동 검증 0; 반자동 0–")
                    .append(originals.size()).append(" (원문 ").append(raw).append("줄, U=").append(originals.size()).append(") | ").append(p[2]).append(" | ").append(p[3]).append(" |\n");
        }
        out.append("\n모든 형식은 기존 OX/MULTIPLE_CHOICE 표현을 우선한다. 자유 입력 단답형을 별도 도입하려면 SavedQuestion·채점·저장 호환 설계가 추가로 필요하며 이번 제안의 수치에 포함하지 않았다. ")
                .append("새 후보를 기존 문제에 더해도 중복·공통 근거·오답 부족·O/X 제약으로 실제 추가 수는 위 상한보다 줄 수 있다.\n\n")
                .append("## 이번 분석으로 알 수 없는 것\n\n")
                .append("- 슬라이드 이미지·도형·색·화살표에만 있는 내용과 OCR로만 읽을 수 있는 정답.\n")
                .append("- PDF에 정답이 없는 빈칸과, 정답은 있으나 줄바꿈·표현 차이로 검색되지 않은 빈칸의 구분.\n")
                .append("- 종결형 후보 문장의 의미적 완결성·진위·중요도 및 핵심 용어처럼 보이는 구.\n")
                .append("- 실제 제목-목록 계층, 다단/표의 열 대응, 강의자의 생략된 맥락·발화.\n")
                .append("- 서로 다른 용어/설명이 의미상 동의어인지, 안전한 오답 3개가 존재하는지.\n")
                .append("- 사용자 검토 후 실제 문항 수·학습 효과. 제안의 0–U는 검토 대상 상한이지 생성 성능 예측이 아니다.\n");
        return out.toString();
    }

    private static void funnel(StringBuilder out,String label,List<Range> r,int pool) {
        int o=r.stream().mapToInt(Range::o).sum(),x=r.stream().mapToInt(Range::x).sum(),f=r.stream().mapToInt(Range::forward).sum(),v=r.stream().mapToInt(Range::reverse).sum();
        out.append("| ").append(label).append(" | ").append(r.stream().mapToInt(Range::lines).sum()).append(" | ").append(r.stream().mapToInt(Range::candidates).sum())
                .append(" | ").append(r.stream().mapToInt(Range::pairs).sum()).append(" | ").append(r.stream().mapToInt(Range::warnings).sum()).append(" | ").append(pool)
                .append(" | ").append(o+x).append(" (").append(o).append('/').append(x).append(") | ").append(f+v).append(" (").append(f).append('/').append(v)
                .append(") | ").append(o+x+f+v).append(" |\n");
    }
    private static void ratio(StringBuilder out,String label,List<Analysis> a) {
        int f=a.stream().mapToInt(v->v.types().get(Kind.F).size()).sum();
        out.append("| ").append(label).append(" | ").append(f).append(" | ").append(percent(f,a.stream().mapToInt(v->v.lines().size()).sum()))
                .append(" | ").append(percent(f,a.stream().mapToInt(v->v.pool().size()).sum())).append(" |\n");
    }
    private static List<Map.Entry<Analysis,Line>> examples(List<Analysis> a,Kind kind) {
        List<Map.Entry<Analysis,Line>> all=new ArrayList<>();
        int max=a.stream().mapToInt(v->v.types().get(kind).size()).max().orElse(0);
        for(int i=0;i<max;i++) for(Analysis p:a) if(i<p.types().get(kind).size()) all.add(Map.entry(p,p.types().get(kind).get(i)));
        List<Map.Entry<Analysis,Line>> chosen=new ArrayList<>(); Set<String> seen=new LinkedHashSet<>();
        for(var e:all) if(chosen.size()<5 && seen.add(key(e.getValue().text()))) chosen.add(e);
        for(var e:all) if(chosen.size()<5 && !chosen.contains(e)) chosen.add(e);
        return chosen;
    }
    private static String key(String text) { return Normalizer.normalize(text,Normalizer.Form.NFKC).replaceAll("(?U)\\s+"," ").strip().toLowerCase(Locale.ROOT); }
    private static String shortText(String text) { return text.codePointCount(0,text.length())>100 ? text.substring(0,text.offsetByCodePoints(0,100))+"…" : text; }
    private static String cell(String text) { return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("|","\\|").replace("\t","⟨TAB⟩"); }
    private static String percent(int value,int total) { return total==0?"0.00%":String.format(Locale.ROOT,"%.2f%%",100.0*value/total); }
    private static String name(Analysis a) { return a.pdf().getFileName().toString(); }
    private static String rangeLabel(Analysis a) { return String.join(", ",a.ranges().stream().map(r->r.first()+"–"+r.last()).toList()); }
}

package com.quiz;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

/** 측정/문서 전용. 이 파일의 검토 표시는 제품 추출 규칙에 사용되지 않는다. */
final class StructuredPdfMeasurements {
    record Generated(QuizGenerator.MultipleChoiceResult mc, QuizGenerator.OxResult ox, List<String> tail) { }
    record Range(int first, int last, TermExtractor.Result legacy, TermExtractor.Extraction structured,
            Generated before, Generated after, int shapePass, int beforeScreen, int afterScreen, int rescued, int duplicateRejected) { }
    record Document(Path file, String hash, List<Range> ranges) {
        String label() { return file.getFileName() + " [" + hash.substring(0, 12) + "]"; }
    }
    static Document read(Path file) throws Exception {
        List<Range> ranges = new ArrayList<>(); PdfReader reader = new PdfReader(); int total = reader.pageCount(file);
        for (int first = 1; first <= total; first += 50) {
            int last = Math.min(first + 49, total); List<String> pages = reader.readRange(file, first, last);
            TermExtractor extractor = new TermExtractor(); var legacy = extractor.extract(pages, first);
            var structured = extractor.extractDetailed(pages, first, TermExtractor.Mode.STRUCTURED);
            assertEquals("LEGACY 순서와 중복 개수까지 보존", legacy.terms(), structured.result().terms().subList(0, legacy.terms().size()));
            assertEquals(structured, extractor.extractDetailed(new ArrayList<>(pages), first, TermExtractor.Mode.STRUCTURED));
            for (int i = 0; i < legacy.evidence().size(); i++) {
                var a = legacy.evidence().get(i); var b = structured.result().evidence().get(i);
                assertEquals(a.term(), b.term()); assertEquals(a.termLine(), b.termLine());
                assertEquals(a.descriptionLines(), b.descriptionLines()); assertEquals(a.interruptions(), b.interruptions());
            }
            QuizGenerator inspector = new QuizGenerator(new Random(0));
            List<Term> rawPass = inspector.screenedTerms(structured.beforeMerge()), pass = inspector.screenedTerms(structured.result());
            Map<String, Long> titles = new LinkedHashMap<>(); for (Term t : structured.result().terms()) titles.merge(SavedQuestion.optionKey(t.term()), 1L, Long::sum);
            int shapePass = 0, beforeScreen = 0, afterScreen = 0, rescued = 0, duplicates = 0;
            for (var e : structured.beforeMerge().evidence()) if (e.sourceKind() == TermExtractor.SourceKind.TITLE_BULLET) {
                if (singlePass(inspector, e)) shapePass++;
                if (rawPass.contains(e.term())) beforeScreen++;
            }
            for (var e : structured.result().evidence()) {
                if (!e.repetitions().isEmpty() && pass.contains(e.term())) rescued++;
                if (titles.get(SavedQuestion.optionKey(e.term().term())) > 1 && singlePass(inspector, e) && !pass.contains(e.term())) duplicates++;
                if (e.sourceKind() != TermExtractor.SourceKind.TITLE_BULLET) continue;
                if (pass.contains(e.term())) afterScreen++;
            }
            ranges.add(new Range(first, last, legacy, structured, generate(legacy), generate(structured.result()), shapePass, beforeScreen, afterScreen, rescued, duplicates));
        }
        return new Document(file, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))), ranges);
    }
    private static boolean singlePass(QuizGenerator inspector, TermExtractor.Evidence e) {
        return !inspector.screenedTerms(new TermExtractor.Result(List.of(e.term()), List.of(), List.of(e))).isEmpty();
    }
    static Generated generate(TermExtractor.Result result) {
        Random random = new Random(7); QuizGenerator generator = new QuizGenerator(random);
        var mc = generator.generateMultipleChoice(result, 30, GenerationOptions.Direction.MIXED); var ox = generator.generateOxDetailed(result);
        return new Generated(mc, ox, List.of(Long.toString(random.nextLong()), Long.toString(random.nextLong()), Long.toString(random.nextLong())));
    }
    static List<Document> samples() throws Exception {
        List<Path> files = new ArrayList<>(List.of(Path.of("sample.pdf")));
        try (var paths = Files.walk(Path.of("samples"))) { files.addAll(paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                && p.toString().toLowerCase(Locale.ROOT).endsWith(".pdf")).sorted().toList()); }
        List<Document> result = new ArrayList<>(); for (Path file : files) result.add(read(file)); return result;
    }
    static Object baseline(List<Document> docs) {
        List<Object> values = new ArrayList<>();
        for (Document doc : docs) {
            List<Object> ranges = new ArrayList<>();
            for (Range range : doc.ranges()) {
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("first", range.first()); row.put("last", range.last()); row.put("extracted", range.structured().result().toString());
                row.put("stats", range.structured().stats().toString()); row.put("multipleChoice", range.after().mc().toString());
                row.put("ox", range.after().ox().toString()); row.put("oxExplanations", range.after().ox().questions().stream().map(QuizGenerator.OxQuestion::explanation).toList());
                row.put("randomTail", range.after().tail()); ranges.add(row);
            }
            values.add(Map.of("pdf", doc.file().getFileName().toString(), "sha256", doc.hash(), "ranges", ranges));
        }
        return Map.of("seed",7,"mode","STRUCTURED","callOrder","MC_MIXED_30 -> OX_ALL","documents",values);
    }
    static String markdown(List<Document> docs) throws Exception {
        StringBuilder out = new StringBuilder("# STRUCTURED 추출 검토\n\n")
                .append("seed 7, 각 최대 50페이지 범위에서 MC 섞기 30→OX 가능한 전부. 표 인식은 구현하지 않았다.\n\n")
                .append("LEGACY는 기존 API·콘솔의 기본값이다. GUI의 PDF 생성만 STRUCTURED를 기본으로 선택한다. 수동 입력은 추출을 거치지 않는다. ")
                .append("원문을 연결할 뿐 문장·정답·오답을 새로 쓰지 않는다. 제목은 screen의 String.length와 같은 UTF-16 24자 상한이다. ")
                .append("상위 표지는 기존 MARKER와 BMP PUA 범위 전체 U+E000–U+F8FF를 사용하며 특정 PUA 코드포인트 예외는 없다. ")
                .append("번호·종결형·미완결 불릿의 이어짐 조각·페이지/빈 줄 경계는 제외한다.\n\n")
                .append("정규화 제목+설명이 모두 같은 구조 쌍만 첫 등장으로 통합하고 반복 페이지/행을 Evidence에 남긴다. ")
                .append("STRUCTURED에서는 기존 콜론 쌍도 정확히 같은 쌍이면 통합하고 반복 출처를 보존한다. 기존 쌍의 내용은 유지하며 같은 제목/다른 설명과 퍼지 유사성은 그대로 기존 screen에 맡긴다. ")
                .append("첫 출처가 저장 문항의 페이지·행이 되며 반복 출처는 추출 Evidence/검토 진단에만 존재한다(JSON 필드 추가 없음).\n\n");
        if (docs.stream().anyMatch(d -> d.hash().startsWith("876e97b15a1d"))) {
            out.append("## 0단계 PDFBox 확인과 표의 한계\n\n")
                .append("구현 전 관찰은 제목 40자 기준의 형태 쌍이었고, 아래 구현의 24자 후보와는 집계 단계가 다르다. ")
                .append("기존 readRange의 sortByPosition/간격 설정으로 확인했다. 상위 제목에도 PUA 표지가 있으며, 7쪽은 같은 표지의 설명이고 '-' 하위 항목이 아니다. ")
                .append("22쪽의 '를 의미' 같은 줄은 앞 불릿의 이어짐이라 새 제목으로 사용하지 않는다. ")
                .append("표는 콜론/탭 없이 열이 합쳐지고, 분류명이 중간에 나오거나 특징이 이름보다 먼저 나와 현재 문자열만으로 열 대응을 안전하게 복원할 수 없다. 표 기능은 추가하지 않았다.\n\n");
        }
        for (Document d : docs) if (d.hash().startsWith("876e97b15a1d")) {
            String[] table = new PdfReader().read(d.file()).get(10).split("\\R", -1);
            for (int[] region : List.of(new int[] {7,10},new int[] {11,13},new int[] {14,16})) {
                out.append("표 원문: ").append(d.label()).append(" 11페이지 ").append(region[0]).append("–").append(region[1]).append("행\n\n```text\n");
                for(int line=region[0];line<=region[1];line++) out.append(shortRaw(table[line-1])).append("\n");
                out.append("```\n\n");
            }
            break;
        }
        out.append("## 모드별 수율\n\n| PDF | 범위 | LEGACY 유효 쌍 / OX / MC | STRUCTURED 유효 쌍 / OX / MC |\n| --- | --- | --- | --- |\n");
        for (Document d : docs) {
            int lp=0,lo=0,lm=0,sp=0,so=0,sm=0;
            for (Range r : d.ranges()) { lp+=r.legacy().terms().size();lo+=r.before().ox().questions().size();lm+=r.before().mc().questions().size();
                sp+=r.structured().result().terms().size();so+=r.after().ox().questions().size();sm+=r.after().mc().questions().size(); }
            out.append("| ").append(d.label()).append(" | ").append(d.ranges().stream().map(r->r.first()+"–"+r.last()).collect(java.util.stream.Collectors.joining(", ")))
                    .append(" | ").append(lp).append(" / ").append(lo).append(" / ").append(lm).append(" | ").append(sp).append(" / ").append(so).append(" / ").append(sm).append(" |\n");
        }
        out.append("\n## 단계별 깔때기(새 구조 후보만)\n\n")
                .append("통합은 실제로 screen **전에** 수행한다. 단독 형태 통과는 기존 screen을 1쌍씩 적용한 값이며 중복 검사를 유예한 진단이다. ")
                .append("실제 통과 전/후를 함께 표시해 중복 통합으로 0→N이 될 수 있음을 보인다. '통합 반복'은 줄어든 출현 수, '구제'는 통합 후 실제 screen을 통과한 대표 Term 수다. ")
                .append("'남은 중복 제외'는 전체 추출 결과 중 단독 형태는 통과하지만 동일 정규화 제목 때문에 최종 탈락한 Term 수이며, LEGACY에서 물려받은 후보도 포함한다.\n\n")
                .append("| PDF | 형태 후보 | 예시·콜론·가드 후 원시 쌍 | 단독 형태 screen 통과 | 통합 전 실제 screen 통과 | 통합 후 새 Term | 통합 후 실제 screen 통과 | 통합 반복 / 구제 / 남은 중복 제외 | 최종 전체 OX / MC |\n")
                .append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |\n");
        for (Document d : docs) {
            int shapes=0,raw=0,shapePass=0,before=0,unique=0,after=0,merged=0,rescued=0,dup=0,ox=0,mc=0;
            for (Range r:d.ranges()) { var s=r.structured().stats(); shapes+=s.shapeCandidates();raw+=s.occurrences();shapePass+=r.shapePass();before+=r.beforeScreen();
                unique+=s.addedTerms();after+=r.afterScreen();merged+=s.mergedOccurrences();rescued+=r.rescued();dup+=r.duplicateRejected();ox+=r.after().ox().questions().size();mc+=r.after().mc().questions().size(); }
            out.append("| ").append(d.label()).append(" | ").append(shapes).append(" | ").append(raw).append(" | ").append(shapePass).append(" | ").append(before)
                    .append(" | ").append(unique).append(" | ").append(after).append(" | ").append(merged).append(" / ").append(rescued).append(" / ").append(dup)
                    .append(" | ").append(ox).append(" / ").append(mc).append(" |\n");
        }
        out.append("\n## 새로 추가된 Term 전체(100 code point 초과는 앞 100자+…; 원래 정의는 유지)\n\n")
                .append("| PDF | 제목 | 설명 | 첫 페이지:제목행 / 설명행 | 반복 페이지:제목행 / 설명행 | 내 검토(규칙에 미사용) |\n| --- | --- | --- | --- | --- | --- |\n");
        for(Document d:docs) {
            int count=0;
            for(Range r:d.ranges()) for(var e:r.structured().result().evidence()) if(e.sourceKind()==TermExtractor.SourceKind.TITLE_BULLET) {
                count++;
                // 알려진 샘플을 사람이 검토한 표시다. 추출/평가 조건에 사용하지 않는다.
                String review=d.hash().startsWith("876e97b15a1d") && e.termLine().page()==16 && e.termLine().lineNumber()==5
                        ? "⚠ 개념명보다 기능을 서술한 제목 같음. 사람 재검토 필요" : "개념명 여부 미확정; 사람 검토 대상";
                out.append("| ").append(d.label()).append(" | ").append(cell(e.term().term())).append(" | ").append(cell(e.term().description()))
                        .append(" | ").append(location(e.termLine(),e.descriptionLines())).append(" | ")
                        .append(e.repetitions().stream().map(o->location(o.title(),o.descriptionLines())).collect(java.util.stream.Collectors.joining("; ")))
                        .append(" | ").append(review).append(" |\n");
            }
            if(count==0) out.append("| ").append(d.label()).append(" | — | 추가된 Term 없음 | — | — | — |\n");
        }
        return out.append("\n## 재현과 한계\n\n제품 screen 조건은 변경하지 않았다. 기존 JSON/결과 검증과 McBaseline*도 변경하지 않는다. ")
                .append("기존 추출 경고는 원 처리 이력으로 유지하므로 STRUCTURED가 연결한 줄을 포함할 수 있다. ")
                .append("미완결 문장·표 열 관계·제목의 개념성은 의미 추측으로 보정하지 않는다. 수율 증가와 사실 정확성은 동일하지 않으므로 새 Term 전부를 원문과 검토해야 한다.\n\n")
                .append("샘플 문서: `mvn test -Dtest=StructuredExtractionReview#writeStructuredReview -Dquiz.writeDocs=true`\n\n")
                .append("별도 폴더 평가: `mvn test -Dtest=StructuredExtractionEvaluation -Dquiz.eval.dir=검토폴더` (기본 표준 출력, docs 쓰기는 -Dquiz.writeDocs=true 필요)\n").toString();
    }
    private static String location(TermExtractor.SourceLine title,List<TermExtractor.SourceLine> lines) {
        return title.page()+":"+title.lineNumber()+" / "+lines.stream().map(TermExtractor.SourceLine::lineNumber).toList();
    }
    private static String cell(String text) {
        String value=shortRaw(text);
        return value.replace("|","\\|").replace("\n","<br>").replace("<","&lt;").replace(">","&gt;");
    }
    private static String shortRaw(String text) { return text.codePointCount(0,text.length())>100?text.substring(0,text.offsetByCodePoints(0,100))+"…":text; }
}

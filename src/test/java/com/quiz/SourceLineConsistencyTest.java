package com.quiz;

import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SourceLineConsistencyTest {
    @Test public void generatedSavedCoordinatesUseTheSamePhysicalLinesAsViewerInBothModes() throws Exception {
        List<String> rows=new ArrayList<>(),mismatches=new ArrayList<>();int total=0;
        Map<Path,String> hashes=new LinkedHashMap<>();PdfPageService service=new PdfPageService();
        for(var sample:OxReviewFixture.samples()){
            assertNull(sample.error());hashes.putIfAbsent(sample.pdf(),PdfPageServiceTest.sha(sample.pdf()));
            var rawPages=new PdfReader().readRange(sample.pdf(),sample.first(),sample.last());
            for(var mode:TermExtractor.Mode.values()){
                var extracted=new TermExtractor().extract(rawPages,sample.first(),mode);var generator=new QuizGenerator(new Random(7));
                var mc=generator.generateMultipleChoice(extracted,30,GenerationOptions.Direction.MIXED).questions();var ox=generator.generateOxDetailed(extracted).questions();
                var set=SavedQuizSet.fromGenerated(sample.pdf().toAbsolutePath().toString(),extracted,ox,mc);
                List<Term> anchors=new ArrayList<>();ox.forEach(q->anchors.add(q.original()));mc.forEach(q->anchors.add(q.original()));
                int checked=0;
                for(int i=0;i<set.questions().size();i++){
                    SavedQuestion q=set.questions().get(i);Term anchor=anchors.get(i);assertNotNull("출처 페이지",q.page());assertNotNull("출처 줄",q.lineNumber());
                    String[] rawLines=rawPages.get(q.page()-sample.first()).split("\\R",-1);
                    assertTrue("저장 줄은 실제 페이지 범위 안",q.lineNumber()>=1&&q.lineNumber()<=rawLines.length);
                    String raw=rawLines[q.lineNumber()-1];var view=service.load(sample.pdf(),q.page());
                    assertTrue("생성 출처 줄은 표시 상한 안",q.lineNumber()<=view.starts().size());
                    assertEquals("PDFBox 실제 줄과 뷰어 줄(빈 줄 포함)",raw,view.line(q.lineNumber()));
                    assertEquals(q.lineNumber().intValue(),view.lineAt(view.starts().get(q.lineNumber()-1)));
                    if(!raw.contains(anchor.term())) {
                        String cause=SavedQuestion.optionKey(raw).contains(SavedQuestion.optionKey(anchor.term()))?"공백/문자 정규화 차이":"원본 제목과 줄의 문자 포함 관계 재검토 필요";
                        mismatches.add(sample.pdf().getFileName()+" "+mode+" "+q.page()+":"+q.lineNumber()+" "+anchor.term()+" — "+cause);
                    }
                    checked++;
                }
                total+=checked;rows.add("| "+sample.pdf().getFileName()+" | "+sample.first()+"–"+sample.last()+" | "+mode+" | "+ox.size()+" | "+mc.size()+" | "+checked+" |");
            }
        }
        for(var entry:hashes.entrySet())assertEquals("PDF SHA-256 불변",entry.getValue(),PdfPageServiceTest.sha(entry.getKey()));
        System.out.println("SOURCE_LINE_CONSISTENCY checked="+total+" viewerLineMismatch=0 literalTitleMismatch="+mismatches.size());
        for(String mismatch:mismatches)System.out.println(mismatch);
        String report="# 원문 보기 좌표 일관성 검증\n\nseed 7, 범위마다 MC 섞기 30→OX 가능한 전부, LEGACY/STRUCTURED 모두 검사.\n\n"
                +"| PDF | 범위 | 모드 | OX | MC | 대조 문항 |\n| --- | --- | --- | ---: | ---: | ---: |\n"+String.join("\n",rows)
                +"\n\n총 "+total+"문항, 뷰어/원문 줄 번호 불일치 0건. 원본 용어 문자 포함 불일치 "+mismatches.size()+"건.\n\n"
                +(mismatches.isEmpty()?"불일치 목록 없음.\n":String.join("\n",mismatches.stream().map(s->"- "+s).toList())+"\n")
                +"\n원문 PDF는 읽기만 했으며 파일별 SHA-256이 전후 동일하다. 기존 기준 캡처/생성기/추출기/저장 스키마는 변경하지 않았다. "
                +"이 문서는 -Dquiz.writeDocs=true일 때만 생성한다.\n";
        if(Boolean.getBoolean("quiz.writeDocs"))Files.writeString(Path.of("docs/pdf-source-line-consistency.md"),report);
    }
}

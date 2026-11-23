package com.quiz;

import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PdfChosenBindingTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static PdfPageService denied(){return new PdfPageService(new PdfPageService.Access(){
        public boolean safeAutomaticFile(Path p){return false;}public int pageCount(Path p){return 1;}public String read(Path p,int n){return "원문";}
    });}
    private static SavedQuizSet set(String source,String parent){return new SavedQuizSet(UUID.randomUUID().toString(),source,List.of(),"제목","",java.time.Instant.EPOCH,parent);}
    @Test public void bindingsAreLimitedToTwoHundredByRegistrationOrderAndNeverWritten()throws Exception{
        var service=denied();Path chosen=temporary.getRoot().toPath().resolve("picked.pdf");var before=NotebookFixtures.snapshot(temporary.getRoot().toPath());
        for(int i=0;i<200;i++)service.remember("set"+i,"shared.pdf",chosen);
        assertNotNull(service.resolve("set0","shared.pdf"));service.remember("set200","shared.pdf",chosen);
        assertNull("조회가 오래된 등록을 연장하면 안 됨",service.resolve("set0","shared.pdf"));
        service.remember("set1","shared.pdf",chosen);service.remember("set201","shared.pdf",chosen);
        assertNotNull(service.resolve("set1","shared.pdf"));assertNull(service.resolve("set2","shared.pdf"));
        int surviving=0;for(int i=0;i<202;i++)if(service.resolve("set"+i,"shared.pdf")!=null)surviving++;
        assertEquals(200,surviving);assertEquals(before,NotebookFixtures.snapshot(temporary.getRoot().toPath()));
        assertNull(denied().resolve("set1","shared.pdf"));
    }
    @Test public void onlyExplicitImmediateParentCanPassItsConnectionToRevisionAndRerevision(){
        var service=denied();var original=set("shared.pdf",null);var first=set(original.sourcePdf(),original.id());var second=set(first.sourcePdf(),first.id());
        assertFalse(service.inheritChosenConnection(original,first));
        Path chosen=temporary.getRoot().toPath().resolve("picked.pdf");service.remember(original.id(),original.sourcePdf(),chosen);
        assertFalse("직전 부모의 연결이 필요",service.inheritChosenConnection(first,second));
        assertTrue(service.inheritChosenConnection(original,first));assertTrue(service.inheritChosenConnection(first,second));
        assertEquals(chosen.toAbsolutePath(),service.resolve(second.id(),second.sourcePdf()).path());
        assertFalse(service.inheritChosenConnection(original,set("different.pdf",original.id())));
        assertFalse(service.inheritChosenConnection(original,set(original.sourcePdf(),UUID.randomUUID().toString())));
        assertFalse(service.inheritChosenConnection(original,original));
        var another=set(first.sourcePdf(),first.id());Path replacement=chosen.resolveSibling("other.pdf");service.remember(another.id(),another.sourcePdf(),replacement);
        assertFalse(service.inheritChosenConnection(first,another));assertEquals(replacement.toAbsolutePath(),service.resolve(another.id(),another.sourcePdf()).path());
    }
    @Test public void automaticEligibilityDoesNotBecomeAnExplicitParentConnection(){
        var service=new PdfPageService(new PdfPageService.Access(){public boolean safeAutomaticFile(Path p){return true;}public int pageCount(Path p){return 1;}public String read(Path p,int n){return "text";}});
        var original=set("C:\\local.pdf",null);var child=set(original.sourcePdf(),original.id());
        assertFalse(service.resolve(original.id(),original.sourcePdf()).chosen());assertFalse(service.inheritChosenConnection(original,child));
        assertFalse(service.resolve(child.id(),child.sourcePdf()).chosen());
    }
    @Test public void explicitlyChosenUncIsMemoryOnlyAndDoesNotUseAutomaticFilesystemCheck()throws Exception{
        int[] checks={0},reads={0};var service=new PdfPageService(new PdfPageService.Access(){
            public boolean safeAutomaticFile(Path p){checks[0]++;throw new AssertionError("UNC 자동 조회 금지");}public int pageCount(Path p){return 1;}
            public String read(Path p,int n){reads[0]++;return "선택한 네트워크 PDF 원문";}
        });
        String source="\\\\server\\share\\lecture.pdf";Path unc=Path.of(source);var before=NotebookFixtures.snapshot(temporary.getRoot().toPath());
        assertNull(service.resolve("set",source));service.remember("set",source,unc);
        assertTrue(service.resolve("set",source).chosen());assertEquals("lecture.pdf",service.resolve("set",source).path().getFileName().toString());
        assertTrue(service.load(unc,1).text().contains("네트워크"));assertEquals(0,checks[0]);assertEquals(1,reads[0]);
        assertNull(service.resolve("other",source));assertEquals(before,NotebookFixtures.snapshot(temporary.getRoot().toPath()));
    }
}

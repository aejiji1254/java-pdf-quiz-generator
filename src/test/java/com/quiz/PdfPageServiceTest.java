package com.quiz;

import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PdfPageServiceTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    public static Path pdf(Path path,boolean blank,boolean encrypted) throws Exception {
        try(var doc=new PDDocument()) {
            PDPage page=new PDPage();doc.addPage(page);
            if(!blank)try(var out=new PDPageContentStream(doc,page)) {
                out.beginText();out.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);out.newLineAtOffset(40,700);
                out.showText("Tiny page");out.endText();
            }
            if(encrypted){var policy=new StandardProtectionPolicy("owner-pass","user-pass",new AccessPermission());policy.setEncryptionKeyLength(128);doc.protect(policy);}
            doc.save(path.toFile());
        }
        return path;
    }
    public static String sha(Path path) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    private static void io(Throwing action) throws Exception {try{action.run();fail("IOException 필요");}catch(IOException expected){}}
    private static void invalid(Throwing action) throws Exception {try{action.run();fail("잘못된 입력 거부 필요");}catch(IllegalArgumentException expected){}}
    interface Throwing {void run() throws Exception;}
    @Test public void displayReadsTinyAndBlankPagesWithoutChangingGenerationValidationOrPdfBytes() throws Exception {
        Path tiny=pdf(temporary.getRoot().toPath().resolve("tiny.pdf"),false,false),blank=pdf(temporary.getRoot().toPath().resolve("blank.pdf"),true,false);
        var before=NotebookFixtures.snapshot(temporary.getRoot().toPath());String tinyHash=sha(tiny),blankHash=sha(blank);var reader=new PdfReader();
        assertTrue(reader.readPageForDisplay(tiny,1).contains("Tiny page"));assertTrue(reader.readPageForDisplay(blank,1).isBlank());
        io(()->reader.readRange(tiny,1,1));io(()->reader.readRange(blank,1,1));
        assertEquals(before,NotebookFixtures.snapshot(temporary.getRoot().toPath()));
        assertEquals(tinyHash,sha(tiny));assertEquals(blankHash,sha(blank));
    }
    @Test public void rejectsOutOfRangeMissingDamagedEncryptedAndWrongExtension() throws Exception {
        Path root=temporary.getRoot().toPath(),tiny=pdf(root.resolve("tiny.pdf"),false,false),encrypted=pdf(root.resolve("locked.pdf"),false,true);
        Path broken=root.resolve("broken.pdf");Files.writeString(broken,"not a pdf");var service=new PdfPageService();
        io(()->service.load(tiny,0));io(()->service.load(tiny,2));io(()->service.load(root.resolve("missing.pdf"),1));
        io(()->service.load(broken,1));io(()->service.load(encrypted,1));io(()->service.choose("set","tiny.pdf",tiny,2));
        io(()->service.choose("set","tiny.pdf",root.resolve("other.txt"),1));
        assertNull(service.resolve("set","tiny.pdf"));
    }
    @Test public void rejectsUntrustedSourceStringsBeforeAnyFilesystemCallbackEvenAfterImport() throws Exception {
        int[] calls={0};var service=new PdfPageService(new PdfPageService.Access(){
            public boolean safeAutomaticFile(Path p){calls[0]++;throw new AssertionError("신뢰하지 않는 경로를 조회하면 안 됨");}
            public int pageCount(Path p){throw new AssertionError();}public String read(Path p,int n){throw new AssertionError();}
        });
        for(String source:List.of("\\\\server\\share\\a.pdf","//server/share/a.pdf","\\\\?\\UNC\\server\\a.pdf","\\\\?\\C:\\a.pdf",
                "https://server/a.pdf","file:///C:/a.pdf","a.pdf","../a.pdf","C:a.pdf","C:\\tmp\\..\\a.pdf","C:/tmp/./a.pdf",
                "수동 입력 (원문 대조 필요: C:\\tmp\\a.pdf)","C:\\a\u0000.pdf","C:\\x\u202e.pdf","C:/NUL.pdf","C://server/a.pdf","C:\\x:stream.pdf")) {
            SavedQuestion q=new SavedQuestion(UUID.randomUUID().toString(),SavedQuestion.Type.OX,"질문",List.of("O","X"),0,"해설",source,1,1);
            SavedQuizSet incoming=new SavedQuizSet(UUID.randomUUID().toString(),source,List.of(q));
            QuizRepository repository=new QuizRepository(temporary.newFolder().toPath());
            Path external=temporary.getRoot().toPath().resolve(UUID.randomUUID()+".json");repository.exportFile(incoming,external);
            var transfer=new QuizTransfer(repository,java.time.Clock.systemUTC());
            var imported=transfer.commit(transfer.inspect(external),false).set();
            assertEquals(source,imported.sourcePdf());assertNull(service.resolve(imported.id(),imported.sourcePdf()));
        }
        assertEquals(0,calls[0]);assertTrue(PdfPageService.automaticSyntax("C:\\folder\\a.PDF"));
    }
    @Test public void automaticLinkRejectionDoesNotReadAndBindingsAreIsolatedBySet() throws Exception {
        int[] reads={0};var service=new PdfPageService(new PdfPageService.Access(){
            public boolean safeAutomaticFile(Path p){return false;}public int pageCount(Path p){return 2;}
            public String read(Path p,int page){reads[0]++;return "alpha\n\nbeta\n";}
        });
        assertNull(service.resolve("one","C:\\linked.pdf"));assertEquals(0,reads[0]);
        Path choice=temporary.getRoot().toPath().resolve("chosen.pdf");
        service.choose("one","friend.pdf",choice,2);assertTrue(service.resolve("one","friend.pdf").chosen());
        assertNull(service.resolve("two","friend.pdf"));assertEquals(choice.toAbsolutePath(),service.resolve("one","friend.pdf").path());
        assertEquals(1,reads[0]);
    }
    @Test public void cacheIsLruTwentyPagesAndTextStorageIsBoundedIndependently() throws Exception {
        List<Integer> reads=new ArrayList<>();var service=new PdfPageService(new PdfPageService.Access(){
            public boolean safeAutomaticFile(Path p){return false;}public int pageCount(Path p){return 100;}
            public String read(Path p,int page){reads.add(page);return "x".repeat(20_001);}
        });Path path=temporary.getRoot().toPath().resolve("large.pdf");
        for(int p=1;p<=20;p++)assertEquals(20_000,service.load(path,p).text().length());
        assertTrue(service.load(path,1).truncated());assertEquals(20,reads.size());
        service.load(path,21);service.load(path,1);assertEquals(21,reads.size());service.load(path,2);assertEquals(22,reads.size());
        service.load(path.resolveSibling("different.pdf"),1);assertEquals(23,reads.size());
        var unicode=PdfPageService.PageText.fromRaw("x".repeat(19_999)+"😀tail");assertEquals(19_999,unicode.text().length());
    }
    @Test public void selectionPreservesTextExceptWhitespaceAndCountsRawCodePoints() throws Exception {
        var page=PdfPageService.PageText.fromRaw("first\r\n\r\n  MiXeD　Ａ😀  \nlast\n");
        assertEquals(List.of(0,6,7,21,26),page.starts());
        int start=page.text().indexOf("  MiXeD"),end=page.text().indexOf("last");
        var selection=PdfPageService.prepare(page,7,start,end);
        assertEquals(3,selection.line());assertEquals("원문 7쪽: MiXeD Ａ😀",selection.explanation());
        assertEquals("",page.line(2));assertEquals("",page.line(5));
        var fiveHundred=PdfPageService.PageText.fromRaw("😀".repeat(500));assertEquals(500,PdfPageService.prepare(fiveHundred,1,0,1000).explanation().substring("원문 1쪽: ".length()).codePointCount(0,1000));
        var tooLong=PdfPageService.PageText.fromRaw("😀".repeat(501));invalid(()->PdfPageService.prepare(tooLong,1,0,1002));
        invalid(()->PdfPageService.prepare(fiveHundred,1,1,1000));invalid(()->PdfPageService.prepare(page,1,0,0));
        invalid(()->PdfPageService.prepare(PdfPageService.PageText.fromRaw(" ".repeat(501)),1,0,501));
    }
}

package com.quiz.gui;

import com.quiz.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.lang.reflect.Modifier;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static com.quiz.gui.PdfSourceGuiTest.*;

public class ChosenPdfGenerationTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private final List<QuizMakerFrame> frames=new ArrayList<>();
    private final List<CountDownLatch> releases=new ArrayList<>();
    private static final Path A=Path.of("D:\\picked\\first.pdf"),B=Path.of("D:\\picked\\second.pdf");
    private interface Producer {QuizWorkflow.Preview run(Path path,int call)throws Exception;}
    private static QuizWorkflow.Preview preview(Path path){
        String source=path.toAbsolutePath().normalize().toString();var q=new SavedQuestion(UUID.randomUUID().toString(),SavedQuestion.Type.OX,"원문 용어 질문",List.of("O","X"),0,"해설",source,1,1,SavedQuestion.Origin.GENERATED,null);
        return new QuizWorkflow.Preview(new SavedQuizSet(UUID.randomUUID().toString(),source,List.of(q)),List.of());
    }
    private static class Input implements QuizMakerFrame.PdfInput {
        Path choice;Producer producer=(p,n)->preview(p);AtomicInteger calls=new AtomicInteger();
        public Path choose(){return choice;}public Path validate(Path p)throws java.io.IOException{return p.toAbsolutePath().normalize();}public int pageCount(Path p)throws java.io.IOException{return 1;}
        public QuizWorkflow.Preview generate(Path p,int first,int last,GenerationOptions o,TermExtractor.Mode mode)throws Exception{return producer.run(p,calls.incrementAndGet());}
    }
    private static PdfPageService denied(){return new PdfPageService(new PdfPageService.Access(){public boolean safeAutomaticFile(Path p){return false;}public int pageCount(Path p){return 1;}public String read(Path p,int n){return "원문 용어\n원문 설명";}});}
    private QuizMakerFrame frame(QuizWorkflow w,PdfPageService service,Input input)throws Exception{
        AtomicReference<QuizMakerFrame> ref=new AtomicReference<>();SwingUtilities.invokeAndWait(()->{var f=new QuizMakerFrame(w,service,input);frames.add(f);f.setVisible(true);ref.set(f);});return ref.get();
    }
    @After public void cleanup()throws Exception{releases.forEach(CountDownLatch::countDown);SwingUtilities.invokeAndWait(()->frames.forEach(QuizMakerFrame::dispose));}
    private static void await(QuizMakerFrame frame,String name)throws Exception{
        AtomicBoolean done=new AtomicBoolean();long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        do{SwingUtilities.invokeAndWait(()->done.set(field(frame,name)==null));if(done.get())return;Thread.sleep(10);}while(System.nanoTime()<limit);fail(name+" 시간 초과");
    }
    private static void choose(QuizMakerFrame f,Input input,Path path)throws Exception{SwingUtilities.invokeAndWait(()->{input.choice=path;JButton b=field(f,"choosePdfButton");assertTrue(b.isEnabled());b.doClick();});await(f,"pageWorker");}
    private static void generate(QuizMakerFrame f)throws Exception{SwingUtilities.invokeAndWait(()->{JButton b=field(f,"generateButton");assertTrue(b.isEnabled());b.doClick();});}
    private static QuizWorkflow.Preview completed(QuizMakerFrame f)throws Exception{await(f,"generationWorker");PdfSourcePanel panel=field(f,"sourceView");waitPanel(panel);AtomicReference<QuizWorkflow.Preview> p=new AtomicReference<>();SwingUtilities.invokeAndWait(()->p.set(field(f,"preview")));return p.get();}
    private static void load(QuizMakerFrame f,Path path)throws Exception{SwingUtilities.invokeAndWait(()->invoke(f,"loadListedQuiz",new Class<?>[]{Path.class},path));await(f,"loadWorker");waitPanel(field(f,"sourceView"));}
    private static void setField(Object o,String name,Object value){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}catch(Exception e){throw new AssertionError(e);}}
    private static void uninterruptibly(CountDownLatch release){boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}}

    @Test public void selectedDeniedPathOpensImmediatelyAndRegistrationDoesNotWriteAnyFiles()throws Exception{
        QuizWorkflow w=NotebookFixtures.workflow(temporary.getRoot().toPath());var service=denied();Input input=new Input();var fixed=preview(A);input.producer=(p,n)->fixed;
        QuizRepository repository=new QuizRepository(temporary.getRoot().toPath().resolve("library"));Path json=repository.save(fixed.set());byte[] bytes=Files.readAllBytes(json);
        var before=NotebookFixtures.snapshot(temporary.getRoot().toPath());var f=frame(w,service,input);choose(f,input,A);generate(f);assertSame(fixed,completed(f));
        assertEquals(before,NotebookFixtures.snapshot(temporary.getRoot().toPath()));assertTrue(service.resolve(fixed.set().id(),fixed.set().sourcePdf()).chosen());
        SwingUtilities.invokeAndWait(()->{PdfSourcePanel panel=field(f,"sourceView");assertTrue(panel.text.getText().contains("원문 용어"));assertEquals("PDF: first.pdf (사용자가 선택한 PDF)",panel.file.getText());});
        Path again=new QuizRepository(temporary.getRoot().toPath().resolve("second-library")).save(fixed.set());assertArrayEquals(bytes,Files.readAllBytes(again));
        assertNull(denied().resolve(fixed.set().id(),fixed.set().sourcePdf()));
    }
    @Test public void sameIdReloadKeepsConnectionButOtherIdAndImportedCopyNeverInheritIt()throws Exception{
        Path root=temporary.getRoot().toPath();QuizWorkflow w=NotebookFixtures.workflow(root);var service=denied();Input input=new Input();var f=frame(w,service,input);
        choose(f,input,A);generate(f);var p=completed(f);var saved=w.saveReviewed(p,Set.of());Path file=root.resolve("quizzes").resolve(saved.id()+".json");load(f,file);
        assertTrue(service.resolve(saved.id(),saved.sourcePdf()).chosen());SwingUtilities.invokeAndWait(()->assertTrue(((PdfSourcePanel)field(f,"sourceView")).file.getText().contains("사용자가 선택한 PDF")));
        var other=preview(A).set();var repo=new QuizRepository(root.resolve("quizzes"));Path otherFile=repo.save(other);load(f,otherFile);assertNull(service.resolve(other.id(),other.sourcePdf()));
        SwingUtilities.invokeAndWait(()->assertTrue(((PdfSourcePanel)field(f,"sourceView")).status.getText().contains("PDF 찾기")));
        byte[] originalBytes=Files.readAllBytes(file);var transfer=new QuizTransfer(repo,java.time.Clock.systemUTC());var copy=transfer.commit(transfer.inspect(file),true);assertNotEquals(saved.id(),copy.set().id());assertEquals(saved.id(),copy.set().derivedFromSetId());assertArrayEquals(originalBytes,Files.readAllBytes(file));
        load(f,copy.path());assertNull(service.resolve(copy.set().id(),copy.set().sourcePdf()));SwingUtilities.invokeAndWait(()->assertTrue(((PdfSourcePanel)field(f,"sourceView")).status.getText().contains("PDF 찾기")));
        var restarted=frame(w,denied(),new Input());load(restarted,file);SwingUtilities.invokeAndWait(()->assertTrue(((PdfSourcePanel)field(restarted,"sourceView")).status.getText().contains("PDF 찾기")));
    }
    @Test public void actualWorkflowGenerationAlsoRegistersPickerReceiptWithoutWritingFiles()throws Exception{
        Path root=temporary.getRoot().toPath(),pdf=root.resolve("picked-sample.pdf");Files.copy(Path.of("sample.pdf"),pdf);String hash=PdfPageServiceTest.sha(pdf);
        QuizWorkflow w=NotebookFixtures.workflow(root);var service=new PdfPageService(new PdfPageService.Access(){public boolean safeAutomaticFile(Path p){return false;}public int pageCount(Path p)throws java.io.IOException{return new PdfReader().pageCount(p);}public String read(Path p,int n)throws java.io.IOException{return new PdfReader().readPageForDisplay(p,n);}});
        Input input=new Input(){@Override public Path validate(Path p)throws java.io.IOException{return w.validatePdfSelection(p);}
            @Override public int pageCount(Path p)throws java.io.IOException{return new PdfReader().pageCount(p);}
            @Override public QuizWorkflow.Preview generate(Path p,int first,int last,GenerationOptions options,TermExtractor.Mode mode)throws Exception{return w.generate(p,first,last,options,mode);}};
        var before=NotebookFixtures.snapshot(root);var f=frame(w,service,input);choose(f,input,pdf);generate(f);var p=completed(f);
        assertFalse(p.set().questions().isEmpty());assertTrue(service.resolve(p.set().id(),p.set().sourcePdf()).chosen());assertEquals(before,NotebookFixtures.snapshot(root));assertEquals(hash,PdfPageServiceTest.sha(pdf));
    }
    @Test public void manualGenerationDoesNotRegisterThePreviouslyPickedPath()throws Exception{
        QuizWorkflow w=NotebookFixtures.workflow(temporary.getRoot().toPath());var service=denied();Input input=new Input();var f=frame(w,service,input);choose(f,input,A);
        var term=new Term("수동 용어","사람이 입력한 명확한 개념의 설명이다.",0);var manual=new ManualTermSet(UUID.randomUUID().toString(),A.toString(),List.of(term));
        SwingUtilities.invokeAndWait(()->{setField(f,"manualSet",manual);DefaultListModel<Term> model=field(f,"manualModel");model.addElement(term);invoke(f,"generateManual",new Class<?>[]{});});
        QuizWorkflow.Preview p=field(f,"preview");assertEquals(manual.sourceLabel(),p.set().sourcePdf());assertNull(service.resolve(p.set().id(),p.set().sourcePdf()));assertEquals(0,input.calls.get());
    }
    private static SavedQuizSet persistRevision(QuizMakerFrame frame,QuizWorkflow workflow,SavedQuizSet original)throws Exception{
        var q=original.questions().getFirst();var edited=workflow.editQuestion(new QuizWorkflow.Preview(original,List.of()),q.id(),q.question()+" 수정",q.options(),q.correctIndex(),q.explanation());
        SwingUtilities.invokeAndWait(()->{
            invoke(frame,"showPreview",new Class<?>[]{QuizWorkflow.Preview.class,SavedQuizSet.class},edited,original);
            javax.swing.Timer closer=new javax.swing.Timer(20,e->{for(java.awt.Window window:java.awt.Window.getWindows())if(window instanceof JDialog dialog&&dialog.getOwner()==frame&&dialog.getTitle().equals("저장 완료"))dialog.dispose();});
            closer.start();try{invoke(frame,"persistReviewed",new Class<?>[]{QuizMetadataPanel.Values.class,boolean.class},new QuizMetadataPanel.Values("수정본",""),false);}finally{closer.stop();}
        });
        return field(frame,"currentSet");
    }
    @Test public void realGuiRevisionAndRerevisionOnlyCarryAnExistingImmediateParentConnection()throws Exception{
        Path root=temporary.getRoot().toPath();QuizWorkflow w=NotebookFixtures.workflow(root);var service=denied();Input input=new Input();var f=frame(w,service,input);
        choose(f,input,A);generate(f);var parent=w.saveReviewed(completed(f),Set.of());
        var first=persistRevision(f,w,parent);assertEquals(parent.id(),first.derivedFromSetId());assertTrue(service.resolve(first.id(),first.sourcePdf()).chosen());
        load(f,root.resolve("quizzes").resolve(first.id()+".json"));var second=persistRevision(f,w,first);assertEquals(first.id(),second.derivedFromSetId());assertTrue(service.resolve(second.id(),second.sourcePdf()).chosen());
        var emptyService=denied();var other=frame(w,emptyService,new Input());load(other,root.resolve("quizzes").resolve(first.id()+".json"));
        var untrusted=persistRevision(other,w,first);assertEquals(first.id(),untrusted.derivedFromSetId());assertNull(emptyService.resolve(untrusted.id(),untrusted.sourcePdf()));
    }
    @Test public void selectedUncIsAllowedOnlyThroughTheExplicitPathAndDisplaysFilenameOnly()throws Exception{
        QuizWorkflow w=NotebookFixtures.workflow(temporary.getRoot().toPath());Input input=new Input();var service=new PdfPageService(new PdfPageService.Access(){public boolean safeAutomaticFile(Path p){throw new AssertionError("UNC 자동 검사 금지");}public int pageCount(Path p){return 1;}public String read(Path p,int n){return "네트워크 원문";}});
        var f=frame(w,service,input);Path unc=Path.of("\\\\server\\share\\lecture.pdf");choose(f,input,unc);generate(f);var p=completed(f);assertTrue(service.resolve(p.set().id(),p.set().sourcePdf()).chosen());
        SwingUtilities.invokeAndWait(()->{PdfSourcePanel panel=field(f,"sourceView");assertEquals("PDF: lecture.pdf (사용자가 선택한 PDF)",panel.file.getText());assertFalse(panel.file.getText().contains("server"));JTextArea status=field(f,"mainStatus");assertFalse(status.getText().contains("server"));});
    }
    @Test public void pickingAnotherPdfDiscardsOldSlowGenerationAndItsConnection()throws Exception{
        QuizWorkflow w=NotebookFixtures.workflow(temporary.getRoot().toPath());var service=denied();Input input=new Input();var old=preview(A);var fresh=preview(B);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),returned=new CountDownLatch(1);releases.add(release);
        input.producer=(p,n)->{if(n==1){entered.countDown();uninterruptibly(release);returned.countDown();return old;}return fresh;};
        var f=frame(w,service,input);choose(f,input,A);generate(f);assertTrue(entered.await(5,TimeUnit.SECONDS));choose(f,input,B);generate(f);assertSame(fresh,completed(f));
        release.countDown();assertTrue(returned.await(5,TimeUnit.SECONDS));SwingUtilities.invokeAndWait(()->assertSame(fresh,field(f,"preview")));
        assertNull(service.resolve(old.set().id(),old.set().sourcePdf()));assertEquals(B.toAbsolutePath(),service.resolve(fresh.set().id(),fresh.set().sourcePdf()).path());
    }
    @Test public void regenerateSupersedesOldJobAndOldFinallyCannotClearNewJobState()throws Exception{
        QuizWorkflow w=NotebookFixtures.workflow(temporary.getRoot().toPath());var service=denied();Input input=new Input();var old=preview(A);var fresh=preview(A);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),returned=new CountDownLatch(1),newEntered=new CountDownLatch(1),newRelease=new CountDownLatch(1);releases.add(release);releases.add(newRelease);
        input.producer=(p,n)->{if(n==1){entered.countDown();uninterruptibly(release);returned.countDown();return old;}newEntered.countDown();uninterruptibly(newRelease);return fresh;};
        var f=frame(w,service,input);choose(f,input,A);generate(f);assertTrue(entered.await(5,TimeUnit.SECONDS));generate(f);assertTrue(newEntered.await(5,TimeUnit.SECONDS));
        AtomicReference<Object> live=new AtomicReference<>();SwingUtilities.invokeAndWait(()->live.set(field(f,"generationWorker")));release.countDown();assertTrue(returned.await(5,TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(()->{assertSame(live.get(),field(f,"generationWorker"));assertTrue((Boolean)field(f,"busy"));assertNull(field(f,"preview"));});
        assertNull(service.resolve(old.set().id(),old.set().sourcePdf()));newRelease.countDown();assertSame(fresh,completed(f));
        SwingUtilities.invokeAndWait(()->{assertSame(fresh,field(f,"preview"));assertNull(field(f,"generationWorker"));assertFalse((Boolean)field(f,"busy"));});
        assertNull(service.resolve(old.set().id(),old.set().sourcePdf()));assertTrue(service.resolve(fresh.set().id(),fresh.set().sourcePdf()).chosen());
    }
    @Test public void runningJobUsesCapturedPathAndLaterUnselectedFieldValueIsNotTrusted()throws Exception{
        QuizWorkflow w=NotebookFixtures.workflow(temporary.getRoot().toPath());var service=denied();Input input=new Input();var captured=preview(A);var unpicked=preview(B);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);releases.add(release);
        input.producer=(p,n)->{if(n==1){assertEquals(A.toAbsolutePath(),p);entered.countDown();uninterruptibly(release);return captured;}assertEquals(B.toAbsolutePath(),p);return unpicked;};
        var f=frame(w,service,input);choose(f,input,A);generate(f);assertTrue(entered.await(5,TimeUnit.SECONDS));SwingUtilities.invokeAndWait(()->setField(f,"selectedPdf",B));release.countDown();assertSame(captured,completed(f));
        assertEquals(A.toAbsolutePath(),service.resolve(captured.set().id(),captured.set().sourcePdf()).path());
        generate(f);assertSame(unpicked,completed(f));assertNull(service.resolve(unpicked.set().id(),unpicked.set().sourcePdf()));
    }
    @Test public void cancelingFilePickerDoesNotReplaceRunningJobOrRegisterAPrematureConnection()throws Exception{
        QuizWorkflow w=NotebookFixtures.workflow(temporary.getRoot().toPath());var service=denied();Input input=new Input();var fixed=preview(A);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);releases.add(release);
        input.producer=(p,n)->{entered.countDown();uninterruptibly(release);return fixed;};var f=frame(w,service,input);choose(f,input,A);generate(f);assertTrue(entered.await(5,TimeUnit.SECONDS));
        AtomicReference<Object> version=new AtomicReference<>();SwingUtilities.invokeAndWait(()->version.set(field(f,"generationVersion")));choose(f,input,null);
        SwingUtilities.invokeAndWait(()->assertEquals(version.get(),field(f,"generationVersion")));assertNull(service.resolve(fixed.set().id(),fixed.set().sourcePdf()));
        release.countDown();assertSame(fixed,completed(f));assertTrue(service.resolve(fixed.set().id(),fixed.set().sourcePdf()).chosen());
    }
    @Test public void defaultConstructorKeepsLocalPolicyAndInjectionConstructorIsPackageOnly()throws Exception{
        QuizWorkflow w=NotebookFixtures.workflow(temporary.getRoot().toPath());AtomicReference<QuizMakerFrame> ref=new AtomicReference<>();SwingUtilities.invokeAndWait(()->{var f=new QuizMakerFrame(w);frames.add(f);ref.set(f);});
        PdfSourcePanel panel=field(ref.get(),"sourceView");PdfPageService pages=field(panel,"service");Object access=field(pages,"access");assertEquals("LocalAccess",access.getClass().getSimpleName());
        var constructor=QuizMakerFrame.class.getDeclaredConstructor(QuizWorkflow.class,PdfPageService.class,QuizMakerFrame.PdfInput.class);
        assertEquals(0,constructor.getModifiers()&(Modifier.PUBLIC|Modifier.PRIVATE|Modifier.PROTECTED));
        for(var c:QuizMakerFrame.class.getConstructors())assertFalse(Arrays.asList(c.getParameterTypes()).contains(PdfPageService.class));
        assertNull(pages.resolve(UUID.randomUUID().toString(),"\\\\server\\share\\untrusted.pdf"));
    }
}

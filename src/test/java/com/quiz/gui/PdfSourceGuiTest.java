package com.quiz.gui;

import com.quiz.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PdfSourceGuiTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @SuppressWarnings("unchecked") static <T>T field(Object o,String name){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return(T)f.get(o);}catch(Exception e){throw new AssertionError(e);}}
    static void invoke(Object o,String name,Class<?>[] types,Object...args){try{var m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(o,args);}catch(Exception e){throw new AssertionError(e);}}
    static SavedQuestion question(String source,Integer page,Integer line){return new SavedQuestion(UUID.randomUUID().toString(),SavedQuestion.Type.OX,"질문",List.of("O","X"),0,"해설",source,page,line,SavedQuestion.Origin.GENERATED,null);}
    static SavedQuizSet set(SavedQuestion...q){return new SavedQuizSet(UUID.randomUUID().toString(),q[0].sourcePdf(),List.of(q));}
    static void waitPanel(PdfSourcePanel panel)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);AtomicBoolean done=new AtomicBoolean();
        do{SwingUtilities.invokeAndWait(()->done.set(panel.worker==null));if(done.get())return;Thread.sleep(10);}while(System.nanoTime()<end);
        fail("원문 worker 시간 초과");
    }
    @Test public void showsExactBlankLineNumbersHighlightAndSelectionAndRejectsOverlongSelection()throws Exception{
        var service=new PdfPageService(new PdfPageService.Access(){public boolean safeAutomaticFile(Path p){return true;}public int pageCount(Path p){return 2;}public String read(Path p,int n){return "first\r\n\r\nTitle A\n"+"z".repeat(501);}});
        var q=question("C:\\lecture.pdf",1,3);var set=set(q);AtomicReference<PdfPageService.Selection> added=new AtomicReference<>();AtomicReference<PdfSourcePanel> ref=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{var panel=new PdfSourcePanel(service,added::set,()->null,s->fail(s));ref.set(panel);panel.showQuestion(set,q);});
        waitPanel(ref.get());SwingUtilities.invokeAndWait(()->{
            var p=ref.get();assertEquals("1\n2\n3\n4",p.numbers.getText());assertFalse(p.text.isEditable());assertEquals("PDF: lecture.pdf (자동 연결)",p.file.getText());
            assertEquals(1,p.text.getHighlighter().getHighlights().length);var highlight=p.text.getHighlighter().getHighlights()[0];assertEquals(7,highlight.getStartOffset());assertEquals(14,highlight.getEndOffset());assertFalse(p.add.isEnabled());
            p.text.select(7,14);assertTrue(p.add.isEnabled());p.add.doClick();assertEquals(new PdfPageService.Selection(1,3,"원문 1쪽: Title A"),added.get());
            p.text.select(15,p.text.getText().length());assertFalse(p.add.isEnabled());assertTrue(p.status.getText().contains("500"));p.stop();
        });
    }
    @Test public void slowOldWorkerCannotOverwriteNewPage()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),returned=new CountDownLatch(1);
        var service=new PdfPageService(new PdfPageService.Access(){public boolean safeAutomaticFile(Path p){return true;}public int pageCount(Path p){return 2;}
            public String read(Path p,int n){if(n==1){entered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}returned.countDown();return "OLD";}return "NEW\nsecond";}});
        var a=question("C:\\lecture.pdf",1,1);var b=question(a.sourcePdf(),2,2);var set=set(a,b);AtomicReference<PdfSourcePanel> ref=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{var p=new PdfSourcePanel(service,s->{},()->null,s->fail(s));ref.set(p);p.showQuestion(set,a);});
        try{assertTrue(entered.await(5,TimeUnit.SECONDS));SwingUtilities.invokeAndWait(()->ref.get().showQuestion(set,b));waitPanel(ref.get());
            SwingUtilities.invokeAndWait(()->assertEquals("NEW\nsecond",ref.get().text.getText()));
        }finally{release.countDown();}
        assertTrue(returned.await(5,TimeUnit.SECONDS));SwingUtilities.invokeAndWait(()->{assertEquals("NEW\nsecond",ref.get().text.getText());assertEquals(4,ref.get().text.getHighlighter().getHighlights()[0].getStartOffset());ref.get().stop();});
    }
    @Test public void openingSourceTabAfterHiddenLoadScrollsToHighlightedLine()throws Exception{
        var service=new PdfPageService(new PdfPageService.Access(){public boolean safeAutomaticFile(Path p){return true;}public int pageCount(Path p){return 1;}
            public String read(Path p,int n){return "row\n".repeat(200);}});
        var q=question("C:\\lecture.pdf",1,180);var set=set(q);AtomicReference<PdfSourcePanel> ref=new AtomicReference<>();AtomicReference<JFrame> frame=new AtomicReference<>();AtomicReference<JTabbedPane> tabs=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{var p=new PdfSourcePanel(service,s->{},()->null,s->fail(s));ref.set(p);var t=new JTabbedPane();tabs.set(t);t.addTab("상세",new JPanel());t.addTab("원문",p);var f=new JFrame();frame.set(f);f.setContentPane(t);f.setSize(600,300);f.setVisible(true);p.showQuestion(set,q);});
        try{
            waitPanel(ref.get());SwingUtilities.invokeAndWait(()->tabs.get().setSelectedIndex(1));
            SwingUtilities.invokeAndWait(()->{});
            SwingUtilities.invokeAndWait(()->assertTrue("숨겨진 탭을 연 뒤 원문 줄로 스크롤",ref.get().scroll.getViewport().getViewPosition().y>0));
        }finally{SwingUtilities.invokeAndWait(()->{ref.get().stop();frame.get().dispose();});}
    }
    @Test public void findCancelWrongPageBindingIsolationAndManualInfoAreExplicit()throws Exception{
        AtomicReference<Path> choice=new AtomicReference<>();AtomicInteger pages=new AtomicInteger(1);List<String> errors=new ArrayList<>();
        var service=new PdfPageService(new PdfPageService.Access(){public boolean safeAutomaticFile(Path p){throw new AssertionError("상대 경로 조회 금지");}public int pageCount(Path p){return pages.get();}public String read(Path p,int n){return "alpha\nbeta";}});
        var q=question("friend.pdf",2,2);var set=set(q);AtomicReference<PdfSourcePanel> ref=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{var p=new PdfSourcePanel(service,s->{},choice::get,errors::add);ref.set(p);p.showQuestion(set,q);});waitPanel(ref.get());
        SwingUtilities.invokeAndWait(()->{var p=ref.get();String status=p.status.getText();p.find.doClick();assertEquals(status,p.status.getText());assertNull(p.worker);choice.set(temporary.getRoot().toPath().resolve("chosen.pdf"));p.find.doClick();});waitPanel(ref.get());
        assertNull(service.resolve(set.id(),set.sourcePdf()));assertEquals(1,errors.size());pages.set(2);
        SwingUtilities.invokeAndWait(()->ref.get().find.doClick());waitPanel(ref.get());SwingUtilities.invokeAndWait(()->{assertEquals("PDF: chosen.pdf (사용자가 선택한 PDF)",ref.get().file.getText());assertFalse(ref.get().file.getText().contains(temporary.getRoot().toString()));assertEquals("PDF 다시 찾기",ref.get().find.getText());});
        assertTrue(service.resolve(set.id(),set.sourcePdf()).chosen());var other=set(q);assertNull(service.resolve(other.id(),other.sourcePdf()));
        var manual=question("수동 입력 (원문 대조 필요: C:\\a.pdf)",null,null);
        SwingUtilities.invokeAndWait(()->{var p=ref.get();p.showQuestion(set(manual),manual);assertEquals("원문 정보가 없습니다",p.status.getText());assertTrue(p.find.isVisible());assertFalse(p.find.isEnabled());assertFalse(p.add.isEnabled());p.stop();});
    }
    @Test public void bothReviewModesSeedBlankAnswerKeepInputOnErrorAndCancelWithoutChangingState()throws Exception{
        QuizWorkflow w=NotebookFixtures.workflow(temporary.getRoot().toPath());var existing=question("friend.pdf",1,1);var original=set(existing);var preview=new QuizWorkflow.Preview(original,List.of());
        var seed=new PdfPageService.Selection(1,1,"원문 1쪽: 선택 내용");
        SwingUtilities.invokeAndWait(()->{
            var frame=new QuizMakerFrame(w);
            try{for(boolean revision:List.of(false,true))for(var type:SavedQuestion.Type.values()){
                invoke(frame,"showPreview",new Class<?>[]{QuizWorkflow.Preview.class,SavedQuizSet.class},preview,revision?original:null);
                JList<SavedQuestion> list=field(frame,"reviewList");JLabel count=field(frame,"reviewCount");String beforeCount=count.getText();var beforeSelected=list.getSelectedValue();
                QuizMakerFrame.createQuestion(w,preview,seed,()->null,e->{fail("유형 취소 후 폼 호출 금지");return 0;},e->fail(),p->fail());
                AtomicInteger attempt=new AtomicInteger(),errors=new AtomicInteger();AtomicReference<QuestionEditorPanel> first=new AtomicReference<>();
                QuizMakerFrame.createQuestion(w,preview,seed,()->type,e->{
                    assertEquals(seed.explanation(),e.explanation.getText());assertEquals(-1,e.answer.getSelectedIndex());
                    if(attempt.getAndIncrement()==0){assertEquals("",e.question.getText());assertTrue(e.options.stream().allMatch(o->o.getText().isEmpty()));first.set(e);e.question.setText("입력 유지 질문");return JOptionPane.OK_OPTION;}
                    assertSame(first.get(),e);assertEquals("입력 유지 질문",e.question.getText());return JOptionPane.CANCEL_OPTION;
                },e->{assertEquals("정답을 선택하세요",e.getMessage());errors.incrementAndGet();},p->fail("취소하면 추가되면 안 됨"));
                assertEquals(1,errors.get());assertSame(preview,field(frame,"preview"));assertEquals(beforeSelected,list.getSelectedValue());assertEquals(beforeCount,count.getText());
                assertEquals(0,new QuestionEditorPanel(type,original.sourcePdf()).answer.getSelectedIndex());
            }}finally{frame.dispose();}
        });
    }
    @Test public void acceptedSourceQuestionIsSelectedCountedAndItsSourceIsHighlighted()throws Exception{
        QuizWorkflow w=NotebookFixtures.workflow(temporary.getRoot().toPath());var old=question("friend.pdf",1,1);var original=set(old);var preview=new QuizWorkflow.Preview(original,List.of());
        Path pdf=PdfPageServiceTest.pdf(temporary.getRoot().toPath().resolve("chosen.pdf"),false,false);String hash=PdfPageServiceTest.sha(pdf);
        AtomicReference<QuizMakerFrame> ref=new AtomicReference<>();AtomicReference<PdfSourcePanel> panel=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{var frame=new QuizMakerFrame(w);ref.set(frame);panel.set(field(frame,"sourceView"));});
        PdfPageService service=field(panel.get(),"service");service.choose(original.id(),original.sourcePdf(),pdf,1);
        SwingUtilities.invokeAndWait(()->{
            var frame=ref.get();invoke(frame,"showPreview",new Class<?>[]{QuizWorkflow.Preview.class,SavedQuizSet.class},preview,original);
            QuizMakerFrame.createQuestion(w,preview,new PdfPageService.Selection(1,1,"원문 1쪽: Tiny"),()->SavedQuestion.Type.OX,e->{e.question.setText("추가 질문");e.answer.setSelectedIndex(0);return JOptionPane.OK_OPTION;},e->fail(e.getMessage()),p->invoke(frame,"acceptAddedQuestion",new Class<?>[]{QuizWorkflow.Preview.class},p));
            JList<SavedQuestion> list=field(frame,"reviewList");assertEquals(1,list.getSelectedIndex());assertEquals(SavedQuestion.Origin.MANUAL,list.getSelectedValue().origin());assertEquals(Integer.valueOf(1),list.getSelectedValue().page());
            JLabel count=field(frame,"reviewCount");assertTrue(count.getText().contains("O/X 2개"));QuizWorkflow.Preview updated=field(frame,"preview");assertTrue(w.hasReviewChanges(original,updated,Set.of()));
            assertFalse(w.hasReviewChanges(original,updated,Set.of(list.getSelectedValue().id())));
        });
        try{waitPanel(panel.get());SwingUtilities.invokeAndWait(()->{assertTrue(panel.get().text.getText().contains("Tiny page"));assertEquals(1,panel.get().text.getHighlighter().getHighlights().length);assertEquals(0,panel.get().text.getHighlighter().getHighlights()[0].getStartOffset());});}
        finally{SwingUtilities.invokeAndWait(ref.get()::dispose);}
        assertEquals(hash,PdfPageServiceTest.sha(pdf));
    }
}

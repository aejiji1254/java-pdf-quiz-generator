package com.quiz.gui;

import com.quiz.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class StructuredExtractionGuiTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @SuppressWarnings("unchecked") private static <T> T field(Object target, String name) {
        try { var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return (T) f.get(target); }
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static void set(Object target,String name,Object value) {
        try {var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static void invoke(QuizMakerFrame frame,String name) {
        try {var m=frame.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(frame);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static void waitForGeneration(QuizMakerFrame frame) throws Exception {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30); AtomicBoolean done=new AtomicBoolean();
        while(!done.get()&&System.nanoTime()<until){SwingUtilities.invokeAndWait(()->done.set(field(frame,"generationWorker")==null));if(!done.get())Thread.sleep(20);}
        assertTrue("PDF 생성 worker 완료",done.get());
    }
    @Test public void guiPdfDefaultsStructuredButExplicitLegacyAndExistingApisAndManualStayLegacy() throws Exception {
        QuizWorkflow workflow=NotebookFixtures.workflow(temporary.getRoot().toPath()); Path pdf=Path.of("samples/c_behavior.pdf.pdf");
        var options=GenerationOptions.defaults();
        var legacy=workflow.generate(pdf,1,32,options);var explicit=workflow.generate(pdf,1,32,options,TermExtractor.Mode.LEGACY);
        assertEquals(legacy.set().questions().stream().map(SavedQuestion::question).toList(),explicit.set().questions().stream().map(SavedQuestion::question).toList());
        assertEquals(1,legacy.set().questions().size());
        var structured=workflow.generate(pdf,1,32,options,TermExtractor.Mode.STRUCTURED);
        assertTrue(structured.diagnostics().contains("구조 규칙으로 추출된 용어 3개"));
        assertTrue(structured.diagnostics().contains("screen 구제 3개"));
        assertTrue(structured.diagnostics().contains("반복 27페이지"));
        for(var q:structured.set().questions())assertEquals(SavedQuestion.Origin.GENERATED,q.origin());
        ManualTermSet manual=new ManualTermSet(UUID.randomUUID().toString(),"",List.of(new Term("Lumera","청록빛 결정을 일정하게 옮기는 절차이다.",0)));
        assertFalse(workflow.generateManual(manual).diagnostics().contains("구조 규칙"));
        AtomicReference<QuizMakerFrame> ref=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{
            QuizMakerFrame frame=new QuizMakerFrame(workflow);ref.set(frame);frame.setVisible(true);
            JComboBox<TermExtractor.Mode> mode=field(frame,"extractionMode"); assertEquals(TermExtractor.Mode.LEGACY,mode.getSelectedItem());
            set(frame,"selectedPdf",pdf);set(frame,"selectedPageCount",32);
            JSpinner last=field(frame,"lastPage");last.setModel(new SpinnerNumberModel(32,1,32,1));invoke(frame,"generate");
        });
        try {
            waitForGeneration(ref.get());
            SwingUtilities.invokeAndWait(()->{
                QuizWorkflow.Preview preview=field(ref.get(),"preview");assertEquals(1,preview.set().questions().size());
                assertFalse(preview.diagnostics().contains("추출 방식: STRUCTURED"));
                JComboBox<TermExtractor.Mode> mode=field(ref.get(),"extractionMode");mode.setSelectedItem(TermExtractor.Mode.STRUCTURED);invoke(ref.get(),"generate");
            });
            waitForGeneration(ref.get());
            SwingUtilities.invokeAndWait(()->{
                JTextArea report=field(ref.get(),"generationDetails");assertTrue(report.getText().contains("추출 방식: STRUCTURED"));
            });
        } finally {SwingUtilities.invokeAndWait(ref.get()::dispose);}
    }
}

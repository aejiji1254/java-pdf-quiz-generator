package com.quiz.gui;

import com.quiz.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SourceQuestionWorkflowTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private QuizWorkflow workflow(){return NotebookFixtures.workflow(temporary.getRoot().toPath());}
    private SavedQuizSet original(){
        var q=new SavedQuestion(UUID.randomUUID().toString(),SavedQuestion.Type.OX,"기존 질문",List.of("O","X"),0,"해설","shared.pdf",1,2,SavedQuestion.Origin.GENERATED,UUID.randomUUID().toString());
        return new SavedQuizSet(UUID.randomUUID().toString(),"shared.pdf",List.of(q));
    }
    private static void invalid(Runnable task){try{task.run();fail("거부해야 함");}catch(IllegalArgumentException expected){}}
    @Test public void bothTypesKeepCoordinatesAndRevisionLineageThroughSavePlayResultAndReview() throws Exception {
        QuizWorkflow w=workflow();SavedQuizSet original=original();QuizRepository repo=new QuizRepository(temporary.getRoot().toPath().resolve("quizzes"));Path originalPath=repo.save(original);
        byte[] originalBytes=Files.readAllBytes(originalPath);var p=new QuizWorkflow.Preview(original,List.of());
        for(var type:SavedQuestion.Type.values())p=w.addQuestion(p,type,"새 질문 "+type,type==SavedQuestion.Type.OX?List.of("O","X"):List.of("가","나","다","라"),1,"원문 2쪽: 선택 문장",2,3);
        assertEquals(1,original.questions().size());assertTrue(w.hasReviewChanges(original,p,Set.of()));
        for(var q:p.set().questions().subList(1,3)){assertEquals(Integer.valueOf(2),q.page());assertEquals(Integer.valueOf(3),q.lineNumber());assertEquals(original.sourcePdf(),q.sourcePdf());assertEquals(SavedQuestion.Origin.MANUAL,q.origin());assertNull(q.derivedFromQuestionId());assertTrue(q.explanation().startsWith("[직접 작성] 원문 2쪽:"));}
        var addedIds=p.set().questions().subList(1,3).stream().map(SavedQuestion::id).collect(java.util.stream.Collectors.toSet());
        assertFalse(w.hasReviewChanges(original,p,addedIds));
        SavedQuestion added=p.set().questions().getLast();p=w.editQuestion(p,added.id(),"편집 질문",added.options(),1,"[직접 작성] 편집 해설");
        var revision=w.saveRevision(original,p,Set.of(),false);var loaded=w.loadQuiz(revision.path());
        assertEquals(original.id(),loaded.derivedFromSetId());assertEquals(original.questions().getFirst().id(),loaded.questions().getFirst().derivedFromQuestionId());
        for(var q:loaded.questions().subList(1,3)){assertNull(q.derivedFromQuestionId());assertEquals(Integer.valueOf(2),q.page());assertEquals(Integer.valueOf(3),q.lineNumber());}
        var session=new QuizSession(loaded,loaded.questions().subList(1,3),null,Clock.systemUTC());
        assertFalse(session.submit("O").correct());assertTrue(session.submit("X").correct());session.next();
        assertFalse(session.submit("1").correct());assertTrue(session.submit("2").correct());session.next();
        Path result=w.saveResult(session);var review=w.loadReview(revision.path(),result);assertEquals(2,review.wrong().size());
        var retry=w.startReview(review);while(!retry.isComplete()){var q=retry.current();retry.submit(q.type()==SavedQuestion.Type.OX?q.options().get(q.correctIndex()):String.valueOf(q.correctIndex()+1));retry.next();}
        assertTrue(w.loadReview(revision.path(),w.saveResult(retry)).wrong().isEmpty());
        assertArrayEquals(originalBytes,Files.readAllBytes(originalPath));
    }
    @Test public void originalProvenanceCannotBeForgedButContentEditingIsAllowed() {
        QuizWorkflow w=workflow();SavedQuizSet original=original();var q=original.questions().getFirst();
        for(int field=0;field<5;field++){
            final int f=field;
            invalid(()->{
                var forged=new SavedQuestion(q.id(),q.type(),q.question(),q.options(),q.correctIndex(),q.explanation(),f==0?"changed.pdf":q.sourcePdf(),f==1?3:q.page(),f==2?4:q.lineNumber(),f==3?SavedQuestion.Origin.MANUAL:q.origin(),f==4?null:q.derivedFromQuestionId());
                w.hasReviewChanges(original,new QuizWorkflow.Preview(new SavedQuizSet(original.id(),original.sourcePdf(),List.of(forged)),List.of()),Set.of());
            });
        }
        var edited=w.editQuestion(new QuizWorkflow.Preview(original,List.of()),q.id(),"수정 질문",List.of("O","X"),1,"수정 해설");
        assertTrue(w.hasReviewChanges(original,edited,Set.of()));assertEquals(q.page(),edited.set().questions().getFirst().page());
        for(var origin:List.of(SavedQuestion.Origin.GENERATED,SavedQuestion.Origin.UNKNOWN)){
            var extra=new SavedQuestion(UUID.randomUUID().toString(),q.type(),"추가 질문",q.options(),0,"해설",q.sourcePdf(),2,3,origin,null);
            invalid(()->w.hasReviewChanges(original,new QuizWorkflow.Preview(original.withQuestions(List.of(q,extra)),List.of()),Set.of()));
        }
        var linked=new SavedQuestion(UUID.randomUUID().toString(),q.type(),"추가 질문",q.options(),0,"해설",q.sourcePdf(),2,3,SavedQuestion.Origin.MANUAL,q.id());
        invalid(()->w.hasReviewChanges(original,new QuizWorkflow.Preview(original.withQuestions(List.of(q,linked)),List.of()),Set.of()));
        invalid(()->new SavedQuestion(UUID.randomUUID().toString(),q.type(),"추가",q.options(),0,"해설",q.sourcePdf(),null,3,SavedQuestion.Origin.MANUAL,null));
    }
    @Test public void chosenPdfPathNeverEntersSavedJsonAndViewingWritesNothing() throws Exception {
        QuizWorkflow w=workflow();SavedQuizSet original=original();Path pdf=PdfPageServiceTest.pdf(temporary.getRoot().toPath().resolve("private-unique-location.pdf"),false,false);
        String hash=PdfPageServiceTest.sha(pdf);var before=NotebookFixtures.snapshot(temporary.getRoot().toPath());PdfPageService service=new PdfPageService();
        var text=service.choose(original.id(),original.sourcePdf(),pdf,1);assertEquals(before,NotebookFixtures.snapshot(temporary.getRoot().toPath()));
        var seed=PdfPageService.prepare(text,1,0,4);
        var p=w.addQuestion(new QuizWorkflow.Preview(original,List.of()),SavedQuestion.Type.OX,"직접 질문",List.of("O","X"),0,seed.explanation(),seed.page(),seed.line());
        var saved=w.saveRevision(original,p,Set.of(),false);String json=Files.readString(saved.path());
        assertFalse(json.contains(pdf.getFileName().toString()));assertFalse(json.contains(pdf.toString()));assertEquals(original.sourcePdf(),saved.set().sourcePdf());
        assertEquals(hash,PdfPageServiceTest.sha(pdf));
        try(var files=Files.list(temporary.getRoot().toPath())){assertEquals(Set.of("private-unique-location.pdf","quizzes"),files.map(x->x.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));}
    }
}
